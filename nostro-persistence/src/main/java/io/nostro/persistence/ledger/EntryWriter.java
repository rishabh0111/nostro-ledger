package io.nostro.persistence.ledger;

import io.nostro.domain.AccountId;
import io.nostro.domain.Entry;
import io.nostro.domain.EntryId;
import io.nostro.domain.LedgerCommand;
import io.nostro.domain.Money;
import io.nostro.domain.Posting;
import io.nostro.domain.RecordEntry;
import io.nostro.domain.RecordOutcome;
import io.nostro.domain.RecordOutcome.AlreadyReversed;
import io.nostro.domain.RecordOutcome.CurrencyMismatch;
import io.nostro.domain.RecordOutcome.IdempotencyKeyReused;
import io.nostro.domain.RecordOutcome.InsufficientBalance;
import io.nostro.domain.RecordOutcome.Recorded;
import io.nostro.domain.RecordOutcome.Refused;
import io.nostro.domain.RecordOutcome.UnknownAccount;
import io.nostro.domain.RecordOutcome.UnknownEntry;
import io.nostro.domain.ReverseEntry;
import io.nostro.domain.TenantId;
import io.nostro.outbox.EntryRecorded;
import io.nostro.outbox.EntryRecorded.PostingRecorded;
import io.nostro.persistence.Installation;
import io.nostro.persistence.entity.AccountEntity;
import io.nostro.persistence.entity.EntryEntity;
import io.nostro.persistence.entity.IdempotencyRecordEntity;
import io.nostro.persistence.entity.OutboxEntity;
import io.nostro.persistence.entity.PostingEntity;
import io.nostro.persistence.entity.TenantScopedId;
import io.nostro.persistence.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.hibernate.StatelessSession;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * One database transaction that records an Entry: the Entry and its Postings, the running-balance
 * move for each Constrained Account touched, the Idempotency Key record, and the outbox row.
 *
 * <p>Every refusal that can be known before writing is decided first and returned as a value with
 * nothing to undo. The one refusal that can only be learned mid-write — a Constrained Account's
 * floor — marks the transaction rollback-only and returns a value too. What escapes as an
 * exception is the database refusing a race (a duplicate Idempotency Key in flight, a second
 * reversal) or aborting for a transient reason; {@link JpaEntryRecorder} handles those from outside
 * this proxy, because Postgres has already aborted the transaction by then (ADR-0014;
 * docs/research/hot-account-contention.md section 3).
 *
 * <p>Nothing here is a {@code save()}: ids are assigned UUIDs, so Spring Data would {@code merge()}
 * and pay a SELECT per row. The stateless session inserts synchronously, batches the Postings, and
 * keeps no persistence context to go stale after the balance UPDATE.
 */
@Component
class EntryWriter {

    /**
     * The floor, in one statement. Postgres re-evaluates the WHERE clause against the updated row
     * after waiting for a lock, so this is safe at READ COMMITTED; it refuses by updating zero rows,
     * which is why the CHECK constraint stands behind it. The guard predicate stays on account
     * columns — a subquery over posting would silently void the guarantee.
     */
    static final String MOVE_BALANCE = """
            UPDATE account
               SET balance_minor = balance_minor + :delta
             WHERE tenant_id = :tenant AND id = :account
               AND (NOT constrained OR balance_minor + :delta >= 0)
            """;

    private final StatelessSession session;
    private final Installation installation;

    EntryWriter(StatelessSession session, Installation installation) {
        this.session = session;
        this.installation = installation;
    }

    @Transactional
    RecordOutcome write(LedgerCommand command) {
        TenantId tenant = TenantContext.required();

        var recorded = session.get(IdempotencyRecordEntity.class, IdempotencyRecordEntity.Id.of(tenant, command.idempotencyKey()));
        if (recorded != null) {
            return replay(tenant, recorded, command);
        }

        List<Posting> postings;
        Optional<EntryId> reverses;
        switch (command) {
            case RecordEntry record -> {
                postings = record.postings();
                reverses = Optional.empty();
            }
            case ReverseEntry reverse -> {
                var original = session.get(EntryEntity.class, TenantScopedId.of(tenant, reverse.reverses().value()));
                if (original == null) {
                    return new UnknownEntry(reverse.reverses());
                }
                if (alreadyReversed(tenant, reverse.reverses())) {
                    return new AlreadyReversed(reverse.reverses());
                }
                postings = postingsOf(tenant, reverse.reverses()).stream().map(Posting::negate).toList();
                reverses = Optional.of(reverse.reverses());
            }
        }

        var imbalance = Entry.imbalance(postings);
        if (imbalance.isPresent()) {
            return imbalance.get();
        }
        var accounts = accountsFor(tenant, postings);
        for (Posting posting : postings) {
            var account = accounts.get(posting.account());
            if (account == null) {
                return new UnknownAccount(posting.account());
            }
            if (!account.currency().equals(posting.amount().currency())) {
                return new CurrencyMismatch(posting.account(), posting.amount().currency(), account.currency());
            }
        }

        var position = installation.position(currentTransactionId());
        var entry = new Entry(EntryId.random(), postings, reverses, command.description());
        var entryRow = new EntryEntity(tenant, entry.id(), reverses, entry.description());
        session.insert(entryRow);
        // Second, so a duplicate key in flight blocks here, before any Account row is locked.
        session.insert(new IdempotencyRecordEntity(tenant, command.idempotencyKey(), command.fingerprint(), entry.id()));
        var postingRows = postings.stream().map(p -> new PostingEntity(entryRow, p)).toList();
        session.insertMultiple(postingRows);

        Optional<Refused> floor = moveConstrainedBalances(tenant, postings, accounts);
        if (floor.isPresent()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return floor.get();
        }

        session.insert(new OutboxEntity(tenant, entry.id(), EntryRecorded.of(tenant, entry, recorded(postingRows), position).toJson()));
        return new Recorded(entry.id(), position);
    }

    /** The Postings as the outbox carries them, each with the id its row was given. */
    private static List<PostingRecorded> recorded(List<PostingEntity> rows) {
        return rows.stream()
                .map(p -> new PostingRecorded(p.getId().id().toString(), p.accountId().toString(),
                        p.getAmount().currency(), p.getAmount().amountMinor()))
                .toList();
    }

    private RecordOutcome replay(TenantId tenant, IdempotencyRecordEntity recorded, LedgerCommand command) {
        if (!recorded.getRequestFingerprint().equals(command.fingerprint())) {
            return new IdempotencyKeyReused(command.idempotencyKey());
        }
        long xid8 = xid8(session
                .createNativeQuery("SELECT position::text FROM entry WHERE tenant_id = :tenant AND id = :id", String.class)
                .setParameter("tenant", tenant.value())
                .setParameter("id", recorded.entryId().value())
                .getSingleResult());
        return new Recorded(recorded.entryId(), installation.position(xid8));
    }

    /**
     * Constrained Accounts only, netted per Account, in ascending id order as separate single-row
     * statements: the documented deadlock prevention. Unconstrained Accounts move no row at all.
     */
    private Optional<Refused> moveConstrainedBalances(
            TenantId tenant, List<Posting> postings, Map<AccountId, AccountEntity> accounts) {
        Map<AccountId, Money> deltas = new TreeMap<>();
        for (Posting posting : postings) {
            if (accounts.get(posting.account()).isConstrained()) {
                deltas.merge(posting.account(), posting.amount(), Money::plus);
            }
        }
        for (var delta : deltas.entrySet()) {
            int moved = session.createNativeMutationQuery(MOVE_BALANCE)
                    .setParameter("delta", delta.getValue().minor())
                    .setParameter("tenant", tenant.value())
                    .setParameter("account", delta.getKey().value())
                    .executeUpdate();
            if (moved == 0) {
                return Optional.of(new InsufficientBalance(delta.getKey()));
            }
        }
        return Optional.empty();
    }

    private Map<AccountId, AccountEntity> accountsFor(TenantId tenant, List<Posting> postings) {
        List<UUID> ids = postings.stream().map(p -> p.account().value()).distinct().toList();
        return session
                .createQuery("from AccountEntity a where a.id.tenantId = :tenant and a.id.id in :ids", AccountEntity.class)
                .setParameter("tenant", tenant.value())
                .setParameter("ids", ids)
                .getResultList().stream()
                .collect(Collectors.toMap(AccountEntity::accountId, Function.identity()));
    }

    private List<Posting> postingsOf(TenantId tenant, EntryId entry) {
        return session
                .createQuery("from PostingEntity p where p.id.tenantId = :tenant and p.entryId = :entry order by p.id.id",
                        PostingEntity.class)
                .setParameter("tenant", tenant.value())
                .setParameter("entry", entry.value())
                .getResultList().stream()
                .map(PostingEntity::toPosting)
                .toList();
    }

    private boolean alreadyReversed(TenantId tenant, EntryId entry) {
        return !session
                .createQuery("select e.id.id from EntryEntity e where e.id.tenantId = :tenant and e.reversesEntryId = :entry",
                        UUID.class)
                .setParameter("tenant", tenant.value())
                .setParameter("entry", entry.value())
                .getResultList().isEmpty();
    }

    /** The xid8 Postgres assigned this transaction: the Position every row written here will carry. */
    private long currentTransactionId() {
        return xid8(session.createNativeQuery("SELECT pg_current_xact_id()::text", String.class).getSingleResult());
    }

    /** pgjdbc has no xid8 type; it travels as text, and it is unsigned. */
    private static long xid8(String text) {
        return Long.parseUnsignedLong(text);
    }
}
