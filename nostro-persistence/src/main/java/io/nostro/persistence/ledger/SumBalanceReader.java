package io.nostro.persistence.ledger;

import io.nostro.domain.AccountId;
import io.nostro.domain.Balance;
import io.nostro.domain.BalanceReader;
import io.nostro.domain.Money;
import io.nostro.domain.Position;
import io.nostro.domain.TenantId;
import io.nostro.persistence.Installation;
import io.nostro.persistence.entity.AccountEntity;
import io.nostro.persistence.entity.TenantScopedId;
import io.nostro.persistence.tenant.TenantContext;
import java.util.Optional;
import org.hibernate.StatelessSession;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The first Balance reader: a {@code SUM} over Postings, behind the contract a projection will serve
 * (ADR-0007). Only the implementation changes there; the shape of the answer does not.
 *
 * <p>The Position reported is that of the newest of the Tenant's Entries the read saw — the same
 * Position a projection that had applied every one of them would report (the largest applied,
 * docs/research/ordering-and-watermarks.md section 5). It is read in the same statement as the
 * sum, so both come from one snapshot: at READ COMMITTED each statement takes its own, and a
 * Position read a statement later could name an Entry the sum did not include. A Tenant with no
 * Entries reflects none of its history, and says so with a zero Position.
 *
 * <p>What that Position promises, exactly: every Entry whose Position a caller can hold, and which
 * compares at or below it, is in the sum. A caller holds a Position only once the transaction that
 * issued it has committed, and a committed Entry is visible to every later snapshot. It does not
 * promise that no Entry with a lower Position is still in flight — Positions are assigned at first
 * write, not at commit — and neither will the projection, which applies a late committer after the
 * higher Positions it was overtaken by. Bounding the Position by the snapshot's {@code xmin} would
 * close that gap and open a worse one: any unrelated open transaction would then make the read
 * report a Position below one the caller legitimately holds.
 *
 * <p>The minimum is accepted and never waited on. A sum over committed rows already includes every
 * Entry whose Position a caller can hold, so the response always satisfies it; the projection is where the wait
 * has something to do.
 */
@Component
class SumBalanceReader implements BalanceReader {

    static final String SUM_AND_POSITION = """
            SELECT coalesce(sum(p.amount_minor), 0)::bigint,
                   (SELECT e.position::text FROM entry e WHERE e.tenant_id = :tenant ORDER BY e.position DESC LIMIT 1)
              FROM posting p
             WHERE p.tenant_id = :tenant AND p.account_id = :account
            """;

    private final StatelessSession session;
    private final Installation installation;

    SumBalanceReader(StatelessSession session, Installation installation) {
        this.session = session;
        this.installation = installation;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Balance> read(AccountId account, Optional<Position> minimum) {
        TenantId tenant = TenantContext.required();
        var accountRow = session.get(AccountEntity.class, TenantScopedId.of(tenant, account.value()));
        if (accountRow == null) {
            return Optional.empty();
        }
        Object[] netAndPosition = session.createNativeQuery(SUM_AND_POSITION, Object[].class)
                .setParameter("tenant", tenant.value())
                .setParameter("account", account.value())
                .getSingleResult();
        var net = Money.ofMinor(((Number) netAndPosition[0]).longValue(), accountRow.currency());
        // pgjdbc has no xid8 type; it travels as text, and it is unsigned.
        var newestEntry = netAndPosition[1] == null ? 0L : Long.parseUnsignedLong((String) netAndPosition[1]);
        return Optional.of(new Balance(account, net, installation.position(newestEntry)));
    }
}
