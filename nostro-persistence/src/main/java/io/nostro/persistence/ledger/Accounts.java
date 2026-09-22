package io.nostro.persistence.ledger;

import io.nostro.domain.Account;
import io.nostro.domain.AccountId;
import io.nostro.persistence.entity.AccountEntity;
import io.nostro.persistence.entity.TenantScopedId;
import io.nostro.persistence.tenant.TenantContext;
import java.util.Optional;
import org.hibernate.StatelessSession;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Opens and finds Accounts for the current Tenant. */
@Component
public class Accounts {

    static final String CODE_UNIQUE_PER_TENANT = "account_code_unique_per_tenant";

    private final StatelessSession session;
    private final TransactionTemplate transaction;

    Accounts(StatelessSession session, PlatformTransactionManager transactionManager) {
        this.session = session;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** What opening an Account returns. A code already in use is an answer, not an exception (ADR-0014). */
    public sealed interface OpenOutcome {
        record Opened(Account account) implements OpenOutcome {
        }

        record CodeTaken(String code) implements OpenOutcome {
        }
    }

    /**
     * Creates the Account. Its Currency and its constrained declaration are fixed from here on.
     *
     * <p>The unique index on {@code (tenant_id, code)} is the only check: a lookup first would race.
     * The transaction is opened here rather than by {@code @Transactional} because the violation
     * aborts it, and the answer has to be decided from outside, once it has rolled back.
     */
    public OpenOutcome open(Account account) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // Joining a caller's transaction would let the violation abort it while the answer
            // below says it was handled. A caller that has one is a programmer error.
            throw new IllegalStateException("Accounts.open decides its answer from outside a transaction and cannot run inside one");
        }
        try {
            transaction.executeWithoutResult(status -> session.insert(new AccountEntity(TenantContext.required(), account)));
            return new OpenOutcome.Opened(account);
        } catch (RuntimeException failure) {
            if (SqlFailure.violates(failure, CODE_UNIQUE_PER_TENANT)) {
                return new OpenOutcome.CodeTaken(account.code());
            }
            throw failure;
        }
    }

    @Transactional(readOnly = true)
    public Optional<Account> find(AccountId id) {
        return Optional.ofNullable(session.get(AccountEntity.class, TenantScopedId.of(TenantContext.required(), id.value())))
                .map(AccountEntity::toAccount);
    }

    /** The stored running balance of a Constrained Account; exists for the floor, and for the tests that check it. */
    @Transactional(readOnly = true)
    public Optional<Long> storedBalanceMinor(AccountId id) {
        return Optional.ofNullable(session.get(AccountEntity.class, TenantScopedId.of(TenantContext.required(), id.value())))
                .map(AccountEntity::getBalanceMinor);
    }
}
