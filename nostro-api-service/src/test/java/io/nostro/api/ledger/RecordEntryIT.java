package io.nostro.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.nostro.api.LedgerIntegrationTest;
import io.nostro.domain.Account;
import io.nostro.domain.AccountId;
import io.nostro.domain.Currency;
import io.nostro.domain.EntryId;
import io.nostro.domain.EntryRecorder;
import io.nostro.domain.IdempotencyKey;
import io.nostro.domain.LedgerCommand;
import io.nostro.domain.Money;
import io.nostro.domain.Position;
import io.nostro.domain.Posting;
import io.nostro.domain.RecordEntry;
import io.nostro.domain.RecordOutcome;
import io.nostro.domain.RecordOutcome.AlreadyReversed;
import io.nostro.domain.RecordOutcome.CurrencyMismatch;
import io.nostro.domain.RecordOutcome.IdempotencyKeyReused;
import io.nostro.domain.RecordOutcome.InsufficientBalance;
import io.nostro.domain.RecordOutcome.Recorded;
import io.nostro.domain.RecordOutcome.Unbalanced;
import io.nostro.domain.RecordOutcome.UnknownAccount;
import io.nostro.domain.RecordOutcome.UnknownEntry;
import io.nostro.domain.ReverseEntry;
import io.nostro.domain.TenantId;
import io.nostro.persistence.ledger.Accounts;
import io.nostro.persistence.outbox.EntryRecorded;
import io.nostro.persistence.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Recording an Entry through the real writer: one transaction, answered with a value. */
class RecordEntryIT extends LedgerIntegrationTest {

    static final Currency USD = Currency.of("USD");
    static final Currency EUR = Currency.of("EUR");

    @Autowired
    EntryRecorder recorder;

    @Autowired
    Accounts accounts;

    @Test
    @DisplayName("a balanced Entry is recorded with its Postings, its Position, and one unpublished outbox row")
    void recordsABalancedEntry() {
        var tenant = newTenant();
        var cash = open(tenant, "cash", USD, false);
        var bank = open(tenant, "bank", USD, false);

        var outcome = record(tenant, new RecordEntry(key(), List.of(
                new Posting(cash, Money.ofMinor(-1_250, USD)), new Posting(bank, Money.ofMinor(1_250, USD))), "rent"));

        var recorded = assertRecorded(outcome);
        var rows = postingRows(tenant, recorded.entry());
        assertThat(rows).extracting(r -> r.get("account_id"), r -> r.get("amount_minor"))
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(cash.value(), -1_250L),
                        org.assertj.core.groups.Tuple.tuple(bank.value(), 1_250L));
        assertThat(positionOf(tenant, recorded.entry())).isEqualTo(recorded.position());

        var outbox = outboxRows(tenant, recorded.entry());
        assertThat(outbox).hasSize(1);
        assertThat(outbox.getFirst()).containsEntry("published_at", null).containsEntry("publish_seq", null);
        assertThat(outbox.getFirst().get("position").toString()).isEqualTo(Long.toUnsignedString(recorded.position().xid8()));
        var payload = EntryRecorded.fromJson(outbox.getFirst().get("payload").toString());
        assertThat(payload.entryId()).isEqualTo(recorded.entry().toString());
        assertThat(payload.position()).isEqualTo(recorded.position().token());
        assertThat(payload.postings()).hasSize(2);
    }

    @Test
    @DisplayName("Positions from successive Entries compare in order")
    void positionsAdvance() {
        var tenant = newTenant();
        var cash = open(tenant, "cash", USD, false);
        var bank = open(tenant, "bank", USD, false);

        var first = assertRecorded(record(tenant, moving(cash, bank, 1)));
        var second = assertRecorded(record(tenant, moving(cash, bank, 2)));

        assertThat(second.position().isAtLeast(first.position())).isTrue();
        assertThat(first.position().isAtLeast(second.position())).isFalse();
    }

    @Test
    @DisplayName("an unbalanced Entry is refused as a value and nothing is written")
    void anUnbalancedEntryIsRefused() {
        var tenant = newTenant();
        var cash = open(tenant, "cash", USD, false);
        var bank = open(tenant, "bank", USD, false);

        var outcome = record(tenant, new RecordEntry(key(), List.of(
                new Posting(cash, Money.ofMinor(-100, USD)), new Posting(bank, Money.ofMinor(99, USD))), null));

        assertThat(outcome).isInstanceOfSatisfying(Unbalanced.class, unbalanced ->
                assertThat(unbalanced.netByCurrency()).containsExactly(Map.entry(USD, Money.ofMinor(-1, USD))));
        assertThat(entryCount(tenant)).isZero();
        assertThat(outboxCount(tenant)).isZero();
    }

    @Test
    @DisplayName("a Posting against another Tenant's Account is refused as unknown, not as forbidden")
    void anotherTenantsAccountIsUnknown() {
        var a = newTenant();
        var b = newTenant();
        var cashOfA = open(a, "cash", USD, false);
        var bankOfB = open(b, "bank", USD, false);

        var outcome = record(a, moving(cashOfA, bankOfB, 100));

        assertThat(outcome).isEqualTo(new UnknownAccount(bankOfB));
        assertThat(entryCount(a)).isZero();
    }

    @Test
    @DisplayName("a Posting in a Currency its Account is not denominated in is refused")
    void aCurrencyMismatchIsRefused() {
        var tenant = newTenant();
        var usd = open(tenant, "usd", USD, false);
        var eur = open(tenant, "eur", EUR, false);

        var outcome = record(tenant, new RecordEntry(key(), List.of(
                new Posting(usd, Money.ofMinor(-100, USD)), new Posting(eur, Money.ofMinor(100, USD))), null));

        assertThat(outcome).isEqualTo(new CurrencyMismatch(eur, USD, EUR));
    }

    @Test
    @DisplayName("a Constrained Account's floor refuses the Entry as a value, and the rollback leaves no outbox row")
    void theFloorRefusesAsAValue() {
        var tenant = newTenant();
        var wallet = open(tenant, "wallet", USD, true);
        var merchant = open(tenant, "merchant", USD, false);
        var funding = open(tenant, "funding", USD, false);
        assertRecorded(record(tenant, moving(funding, wallet, 100)));

        var outcome = record(tenant, moving(wallet, merchant, 150));

        assertThat(outcome).isEqualTo(new InsufficientBalance(wallet));
        assertThat(entryCount(tenant)).isEqualTo(1);
        assertThat(outboxCount(tenant)).isEqualTo(1);
        assertThat(storedBalance(tenant, wallet)).isEqualTo(100);
    }

    @Test
    @DisplayName("an unconstrained Account may go negative and moves no balance row")
    void anUnconstrainedAccountHasNoFloor() {
        var tenant = newTenant();
        var overdraft = open(tenant, "overdraft", USD, false);
        var merchant = open(tenant, "merchant", USD, false);

        assertRecorded(record(tenant, moving(overdraft, merchant, 500)));

        assertThat(storedBalance(tenant, overdraft)).as("no running balance is kept").isZero();
        assertThat(sumOfPostings(tenant, overdraft)).isEqualTo(-500);
    }

    @Test
    @DisplayName("the same Idempotency Key returns what the first attempt returned; the same key with another request is refused")
    void idempotencyReplaysTheFirstOutcome() {
        var tenant = newTenant();
        var cash = open(tenant, "cash", USD, false);
        var bank = open(tenant, "bank", USD, false);
        var key = key();

        var first = assertRecorded(record(tenant, new RecordEntry(key, postings(cash, bank, 100), "rent")));
        var replay = record(tenant, new RecordEntry(key, postings(cash, bank, 100), "rent"));
        var reused = record(tenant, new RecordEntry(key, postings(cash, bank, 101), "rent"));

        assertThat(replay).isEqualTo(first);
        assertThat(reused).isEqualTo(new IdempotencyKeyReused(key));
        assertThat(entryCount(tenant)).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent presentations of one Idempotency Key record one Entry and all receive it")
    void concurrentDuplicatesRecordOnce() throws Exception {
        var tenant = newTenant();
        var cash = open(tenant, "cash", USD, false);
        var bank = open(tenant, "bank", USD, false);
        var command = new RecordEntry(key(), postings(cash, bank, 100), "rent");

        var outcomes = Concurrently.run(16, () -> record(tenant, command));

        assertThat(outcomes).hasSize(16).allSatisfy(o -> assertThat(o).isInstanceOf(Recorded.class));
        assertThat(outcomes.stream().distinct()).hasSize(1);
        assertThat(entryCount(tenant)).isEqualTo(1);
    }

    @Test
    @DisplayName("a Reversing Entry negates every Posting, at most once, and is subject to the floor")
    void reversals() {
        var tenant = newTenant();
        var wallet = open(tenant, "wallet", USD, true);
        var merchant = open(tenant, "merchant", USD, false);
        var funding = open(tenant, "funding", USD, false);
        var fund = assertRecorded(record(tenant, moving(funding, wallet, 100)));
        var spend = assertRecorded(record(tenant, moving(wallet, merchant, 60)));

        var unknown = record(tenant, new ReverseEntry(key(), EntryId.random(), null));
        var reversalRefused = record(tenant, new ReverseEntry(key(), fund.entry(), "unwind funding"));
        var reversal = assertRecorded(record(tenant, new ReverseEntry(key(), spend.entry(), "reverse the spend")));
        var twice = record(tenant, new ReverseEntry(key(), spend.entry(), "reverse the spend again"));

        assertThat(unknown).isInstanceOf(UnknownEntry.class);
        assertThat(reversalRefused).as("the wallet holds 40; unwinding 100 would breach the floor")
                .isEqualTo(new InsufficientBalance(wallet));
        assertThat(postingRows(tenant, reversal.entry())).extracting(r -> r.get("account_id"), r -> r.get("amount_minor"))
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(wallet.value(), 60L),
                        org.assertj.core.groups.Tuple.tuple(merchant.value(), -60L));
        assertThat(twice).isEqualTo(new AlreadyReversed(spend.entry()));
        assertThat(storedBalance(tenant, wallet)).isEqualTo(100);
    }

    @Test
    @DisplayName("an Entry with N Postings inserts them in one batched statement")
    void postingsAreBatched() {
        var tenant = newTenant();
        var accounts = new java.util.ArrayList<AccountId>();
        for (int i = 0; i < 20; i++) {
            accounts.add(open(tenant, "a" + i, USD, false));
        }
        var postings = new java.util.ArrayList<Posting>();
        for (int i = 0; i < 20; i++) {
            postings.add(new Posting(accounts.get(i), Money.ofMinor(i % 2 == 0 ? -7 : 7, USD)));
        }
        var statistics = statistics();
        statistics.clear();

        assertRecorded(record(tenant, new RecordEntry(key(), postings, "fan-out")));

        // Idempotency lookup, account lookup, xid, entry, idempotency record, ONE posting batch,
        // outbox: seven statements for a twenty-Posting Entry. Twenty-six would mean no batching.
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(8);
        assertThat(postingRows(tenant, lastEntry(tenant))).hasSize(20);
    }

    @Test
    @DisplayName("an UPDATE of a Posting is refused: by ORM 7 for the HQL path, and by the database for every other")
    void aPostingCannotBeUpdated() {
        var tenant = newTenant();
        var cash = open(tenant, "cash", USD, false);
        var bank = open(tenant, "bank", USD, false);
        var recorded = assertRecorded(record(tenant, moving(cash, bank, 100)));

        // ORM 7 default: a bulk update against an @Immutable entity throws before reaching the database.
        var hql = catchThrowable(() -> inTransactionAs(tenant, () -> statelessSession()
                .createMutationQuery("update PostingEntity p set p.accountId = :account where p.entryId = :entry")
                .setParameter("account", bank.value())
                .setParameter("entry", recorded.entry().value())
                .executeUpdate()));
        // Native SQL through the same session is the bypass @Immutable never covered. The barrier
        // is the revoked grant on the request-path role, and it fails at the database.
        var nativeSql = catchThrowable(() -> inTransactionAs(tenant, () -> statelessSession()
                .createNativeMutationQuery("UPDATE posting SET account_id = :account WHERE entry_id = :entry")
                .setParameter("account", bank.value())
                .setParameter("entry", recorded.entry().value())
                .executeUpdate()));

        assertThat(hql).isNotNull();
        assertThat(sqlState(hql)).as("refused in Java, before any statement").isNull();
        assertThat(sqlState(nativeSql)).as("refused by the revoked grant, SQLSTATE 42501").isEqualTo("42501");
        assertThat(postingRows(tenant, recorded.entry())).extracting(r -> r.get("account_id"))
                .containsExactlyInAnyOrder(cash.value(), bank.value());
    }

    @Test
    @DisplayName("concurrent writers to one Constrained Account: only floor refusals, and the balance never goes negative")
    void concurrentWritersToOneConstrainedAccount() throws Exception {
        var tenant = newTenant();
        var wallet = open(tenant, "wallet", USD, true);
        var merchant = open(tenant, "merchant", USD, false);
        var funding = open(tenant, "funding", USD, false);
        assertRecorded(record(tenant, moving(funding, wallet, 1_000)));

        var outcomes = Concurrently.run(40, () -> record(tenant, moving(wallet, merchant, 100)));

        assertThat(outcomes).hasSize(40);
        assertThat(outcomes.stream().filter(Recorded.class::isInstance)).hasSize(10);
        assertThat(outcomes.stream().filter(o -> !(o instanceof Recorded)))
                .hasSize(30)
                .allSatisfy(o -> assertThat(o).isEqualTo(new InsufficientBalance(wallet)));
        assertThat(storedBalance(tenant, wallet)).isZero();
        assertThat(sumOfPostings(tenant, wallet)).as("stored balance agrees with SUM over Postings").isZero();
        assertThat(outboxCount(tenant)).isEqualTo(11);
    }

    @Test
    @DisplayName("Entries touching two Constrained Accounts in opposite orders never deadlock: balances are moved in id order")
    void oppositeOrderWritersDoNotDeadlock() throws Exception {
        var tenant = newTenant();
        var left = open(tenant, "left", USD, true);
        var right = open(tenant, "right", USD, true);
        var funding = open(tenant, "funding", USD, false);
        assertRecorded(record(tenant, moving(funding, left, 100_000)));
        assertRecorded(record(tenant, moving(funding, right, 100_000)));

        // Each writer alternates direction, so at any moment some are posting left->right and
        // others right->left. Without a deterministic lock order this is the textbook deadlock.
        var outcomes = Concurrently.run(24, () -> {
            var results = new java.util.ArrayList<RecordOutcome>();
            for (int i = 0; i < 10; i++) {
                results.add(record(tenant, i % 2 == 0 ? moving(left, right, 1) : moving(right, left, 1)));
            }
            return results;
        });

        assertThat(outcomes.stream().flatMap(List::stream)).hasSize(240).allSatisfy(o -> assertThat(o).isInstanceOf(Recorded.class));
        assertThat(storedBalance(tenant, left) + storedBalance(tenant, right)).isEqualTo(200_000);
        assertThat(storedBalance(tenant, left)).isEqualTo(sumOfPostings(tenant, left));
        assertThat(storedBalance(tenant, right)).isEqualTo(sumOfPostings(tenant, right));
    }

    // -- helpers ---------------------------------------------------------------------------------

    private RecordOutcome record(TenantId tenant, LedgerCommand command) {
        return TenantContext.runAs(tenant, () -> recorder.record(command));
    }

    private AccountId open(TenantId tenant, String code, Currency currency, boolean constrained) {
        var account = new Account(AccountId.random(), code, currency, constrained);
        return TenantContext.runAs(tenant, () -> accounts.open(account)).id();
    }

    private static RecordEntry moving(AccountId from, AccountId to, long minor) {
        return new RecordEntry(key(), postings(from, to, minor), null);
    }

    private static List<Posting> postings(AccountId from, AccountId to, long minor) {
        return List.of(new Posting(from, Money.ofMinor(-minor, USD)), new Posting(to, Money.ofMinor(minor, USD)));
    }

    private static IdempotencyKey key() {
        return new IdempotencyKey(UUID.randomUUID().toString());
    }

    private static Recorded assertRecorded(RecordOutcome outcome) {
        assertThat(outcome).isInstanceOf(Recorded.class);
        return (Recorded) outcome;
    }

    private org.hibernate.StatelessSession statelessSession() {
        return io.nostro.persistence.TransactionalStatelessSession.current(entityManagerFactory);
    }

    @Autowired
    jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private org.hibernate.stat.Statistics statistics() {
        return entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
    }

    private List<Map<String, Object>> postingRows(TenantId tenant, EntryId entry) {
        return inTransactionAs(tenant, () -> jdbc()
                .sql("SELECT account_id, amount_minor, currency FROM posting WHERE entry_id = ?")
                .param(entry.value()).query().listOfRows());
    }

    private List<Map<String, Object>> outboxRows(TenantId tenant, EntryId entry) {
        return inTransactionAs(tenant, () -> jdbc()
                .sql("SELECT position::text AS position, payload::text AS payload, published_at, publish_seq FROM outbox WHERE entry_id = ?")
                .param(entry.value()).query().listOfRows());
    }

    private Position positionOf(TenantId tenant, EntryId entry) {
        long xid = inTransactionAs(tenant, () -> Long.parseUnsignedLong(jdbc()
                .sql("SELECT position::text FROM entry WHERE id = ?").param(entry.value()).query(String.class).single()));
        return new Position(installationId(), xid);
    }

    private long installationId() {
        return asOwner().sql("SELECT system_identifier FROM installation").query(Long.class).single();
    }

    private EntryId lastEntry(TenantId tenant) {
        return new EntryId(inTransactionAs(tenant, () -> jdbc()
                .sql("SELECT id FROM entry ORDER BY recorded_at DESC LIMIT 1").query(UUID.class).single()));
    }

    private long entryCount(TenantId tenant) {
        return inTransactionAs(tenant, () -> jdbc().sql("SELECT count(*) FROM entry").query(Long.class).single());
    }

    private long outboxCount(TenantId tenant) {
        return inTransactionAs(tenant, () -> jdbc()
                .sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single());
    }

    private long storedBalance(TenantId tenant, AccountId account) {
        return TenantContext.runAs(tenant, () -> accounts.storedBalanceMinor(account)).orElseThrow();
    }

    private long sumOfPostings(TenantId tenant, AccountId account) {
        return inTransactionAs(tenant, () -> jdbc()
                .sql("SELECT coalesce(sum(amount_minor), 0) FROM posting WHERE account_id = ?")
                .param(account.value()).query(Long.class).single());
    }
}
