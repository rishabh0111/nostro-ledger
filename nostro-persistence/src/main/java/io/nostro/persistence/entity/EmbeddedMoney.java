package io.nostro.persistence.entity;

import io.nostro.domain.Currency;
import io.nostro.domain.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * {@link Money} as it sits in a row: the minor units and the Currency code. The domain's
 * {@code Currency} carries its scale too, which is derivable from the code and is not stored.
 *
 * <p>Kept to plain values with no collections, per the record-embeddable guidance.
 */
@Embeddable
public record EmbeddedMoney(
        @Column(name = "amount_minor", nullable = false) long amountMinor,
        @Column(name = "currency", nullable = false) String currency) {

    public static EmbeddedMoney of(Money money) {
        return new EmbeddedMoney(money.minor(), money.currency().code());
    }

    public Money toMoney() {
        return Money.ofMinor(amountMinor, Currency.of(currency));
    }
}
