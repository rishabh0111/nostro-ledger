package io.nostro.domain;

import java.util.Objects;

/**
 * A named place within a Tenant against which value accumulates, denominated in a single Currency.
 *
 * <p>A <em>Constrained Account</em> is one declared, at creation, never to hold a negative Balance
 * (ADR-0004). Neither the Currency nor the declaration ever changes.
 *
 * @param id          the identity within its Tenant
 * @param code        the name the Tenant gave it, unique within the Tenant
 * @param currency    the single Currency it is denominated in
 * @param constrained whether its Balance may never go below zero
 */
public record Account(AccountId id, String code, Currency currency, boolean constrained) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currency, "currency");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("account code must not be blank");
        }
    }
}
