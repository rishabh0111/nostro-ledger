package io.nostro.persistence.ledger;

import io.nostro.domain.Account;
import io.nostro.domain.AccountId;
import io.nostro.domain.TenantId;
import io.nostro.persistence.entity.AccountEntity;
import io.nostro.persistence.entity.TenantScopedId;
import io.nostro.persistence.tenant.TenantContext;
import java.util.Optional;
import org.hibernate.StatelessSession;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Opens and finds Accounts for the current Tenant. */
@Component
public class Accounts {

    private final StatelessSession session;

    Accounts(StatelessSession session) {
        this.session = session;
    }

    /** Creates the Account. Its Currency and its constrained declaration are fixed from here on. */
    @Transactional
    public Account open(Account account) {
        session.insert(new AccountEntity(currentTenant(), account));
        return account;
    }

    @Transactional(readOnly = true)
    public Optional<Account> find(AccountId id) {
        return Optional.ofNullable(session.get(AccountEntity.class, TenantScopedId.of(currentTenant(), id.value())))
                .map(AccountEntity::toAccount);
    }

    /** The stored running balance of a Constrained Account; exists for the floor, and for the tests that check it. */
    @Transactional(readOnly = true)
    public Optional<Long> storedBalanceMinor(AccountId id) {
        return Optional.ofNullable(session.get(AccountEntity.class, TenantScopedId.of(currentTenant(), id.value())))
                .map(AccountEntity::getBalanceMinor);
    }

    private static TenantId currentTenant() {
        return TenantContext.current().orElseThrow(() -> new IllegalStateException("no Tenant is bound"));
    }
}
