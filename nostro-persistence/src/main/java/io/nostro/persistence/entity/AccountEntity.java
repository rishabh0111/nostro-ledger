package io.nostro.persistence.entity;

import io.nostro.domain.Account;
import io.nostro.domain.AccountId;
import io.nostro.domain.Currency;
import io.nostro.domain.TenantId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An Account row. Not {@code @Immutable}: a Constrained Account's {@code balance_minor} moves with
 * every Entry that touches it. That column is never written through this entity — dirty checking
 * would be a read-modify-write from a stale snapshot — but by the guarded single-statement UPDATE
 * in the Entry writer (docs/research/hot-account-contention.md section 7). Everything else the
 * schema keeps immutable.
 */
@Entity
@Table(name = "account")
public class AccountEntity {

    @EmbeddedId
    private TenantScopedId id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Column(name = "constrained", nullable = false, updatable = false)
    private boolean constrained;

    @Column(name = "balance_minor", nullable = false, insertable = false, updatable = false)
    private long balanceMinor;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected AccountEntity() {
    }

    public AccountEntity(TenantId tenant, Account account) {
        this.id = TenantScopedId.of(tenant, account.id().value());
        this.code = account.code();
        this.currency = account.currency().code();
        this.constrained = account.constrained();
    }

    public TenantScopedId getId() {
        return id;
    }

    public AccountId accountId() {
        return new AccountId(id.id());
    }

    public String getCode() {
        return code;
    }

    public Currency currency() {
        return Currency.of(currency);
    }

    public boolean isConstrained() {
        return constrained;
    }

    /** The stored running balance, meaningful only for a Constrained Account and only for the floor. */
    public long getBalanceMinor() {
        return balanceMinor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Account toAccount() {
        return new Account(accountId(), code, currency(), constrained);
    }
}
