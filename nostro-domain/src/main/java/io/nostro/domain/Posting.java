package io.nostro.domain;

import java.util.Objects;

/**
 * A signed Amount applied to exactly one Account, as one part of an Entry. A Posting never exists
 * on its own.
 *
 * <p>A zero Amount is not a Posting. It would balance trivially and record nothing, so it is
 * refused at construction rather than tolerated.
 */
public record Posting(AccountId account, Money amount) {

    public Posting {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(amount, "amount");
        if (amount.isZero()) {
            throw new IllegalArgumentException("a posting must carry a non-zero amount");
        }
    }

    public Posting negate() {
        return new Posting(account, amount.negate());
    }
}
