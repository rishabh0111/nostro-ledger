package io.nostro.domain;

import java.util.Objects;
import java.util.UUID;

/** Identifies an Account within its Tenant. Never meaningful without the Tenant. */
public record AccountId(UUID value) implements Comparable<AccountId> {

    public AccountId {
        Objects.requireNonNull(value, "value");
    }

    public static AccountId random() {
        return new AccountId(UUID.randomUUID());
    }

    /** Accounts are touched in this order when an Entry moves several balances, so two writers never deadlock. */
    @Override
    public int compareTo(AccountId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
