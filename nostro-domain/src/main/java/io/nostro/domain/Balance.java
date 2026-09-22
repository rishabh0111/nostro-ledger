package io.nostro.domain;

import java.util.Objects;

/**
 * The net of every Posting against an Account, and the Position it reflects (ADR-0007).
 *
 * <p>A Balance is never reported alone. The Position says how much of the Tenant's history the
 * figure includes, so that a figure served from something that lags is a disclosed fact rather
 * than a lie; a caller that needs read-your-writes presents the Position its write returned as a
 * minimum on the read.
 *
 * @param account  the Account the net is of
 * @param amount   the net, in the Account's Currency
 * @param position how much of the Tenant's history this figure reflects
 */
public record Balance(AccountId account, Money amount, Position position) {

    public Balance {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(position, "position");
    }
}
