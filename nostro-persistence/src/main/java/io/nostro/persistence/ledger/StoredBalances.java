package io.nostro.persistence.ledger;

import io.nostro.domain.AccountId;
import io.nostro.persistence.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.hibernate.StatelessSession;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The check behind ADR-0004's invariant: a Constrained Account's stored running balance — the one
 * the floor is enforced on — equals the sum of its Postings. CI asserts it after specific writes;
 * this lets the running system assert it about itself, continuously.
 */
@Component
public class StoredBalances {

    /**
     * One statement, so the stored balances and the sums come from one snapshot. The stored balance
     * and the Postings that moved it are written in one transaction, so no snapshot can see one
     * without the other, and a concurrent write cannot produce a false alarm.
     */
    static final String DISAGREEING = """
            SELECT a.id
              FROM account a
             WHERE a.tenant_id = :tenant AND a.constrained
               AND a.balance_minor <> (SELECT coalesce(sum(p.amount_minor), 0)
                                         FROM posting p
                                        WHERE p.tenant_id = a.tenant_id AND p.account_id = a.id)
             ORDER BY a.id
            """;

    private final StatelessSession session;

    StoredBalances(StatelessSession session) {
        this.session = session;
    }

    /** The current Tenant's Constrained Accounts whose stored balance is not the sum of their Postings. */
    @Transactional(readOnly = true)
    public List<AccountId> disagreeingWithTheirPostings() {
        return session.createNativeQuery(DISAGREEING, UUID.class)
                .setParameter("tenant", TenantContext.required().value())
                .getResultList().stream()
                .map(AccountId::new)
                .toList();
    }
}
