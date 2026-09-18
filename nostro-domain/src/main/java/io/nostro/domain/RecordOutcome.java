package io.nostro.domain;

import java.util.Map;

/**
 * What recording an Entry returns. Expected failures are answers, not exceptions (ADR-0014): the
 * hierarchy is sealed so that a switch over it is exhaustive, and adding a failure mode without
 * mapping it to a response is a compile error.
 */
public sealed interface RecordOutcome permits RecordOutcome.Recorded, RecordOutcome.Refused {

    /**
     * The Entry was recorded, or had already been recorded under this Idempotency Key, in which case
     * this is what the first attempt returned.
     */
    record Recorded(EntryId entry, Position position) implements RecordOutcome {
    }

    /** The Entry was refused. Every refusal the ledger can express is a member. */
    sealed interface Refused extends RecordOutcome
            permits Unbalanced, UnknownAccount, CurrencyMismatch, InsufficientBalance,
                    IdempotencyKeyReused, UnknownEntry, AlreadyReversed {
    }

    /** The Postings do not sum to zero in every Currency they touch. Carries only the Currencies that fail. */
    record Unbalanced(Map<Currency, Money> netByCurrency) implements Refused {
    }

    /** A Posting names an Account this Tenant does not have. Indistinguishable from another Tenant's. */
    record UnknownAccount(AccountId account) implements Refused {
    }

    /** A Posting is in a Currency other than the one its Account is denominated in. */
    record CurrencyMismatch(AccountId account, Currency posted, Currency denominated) implements Refused {
    }

    /** The Entry would take a Constrained Account below zero (ADR-0004). */
    record InsufficientBalance(AccountId account) implements Refused {
    }

    /** The Idempotency Key was presented before with a different request (ADR-0005). */
    record IdempotencyKeyReused(IdempotencyKey key) implements Refused {
    }

    /** A Reversing Entry names an Entry this Tenant does not have. */
    record UnknownEntry(EntryId entry) implements Refused {
    }

    /** The Entry named has already been reversed; an Entry is reversed at most once. */
    record AlreadyReversed(EntryId entry) implements Refused {
    }
}
