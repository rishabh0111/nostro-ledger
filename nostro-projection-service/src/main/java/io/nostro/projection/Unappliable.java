package io.nostro.projection;

/**
 * A message the projection will not apply, and will not skip either: it halts its partition
 * (ADR-0010). Something upstream wrote what the ledger could not have — an Entry that does not
 * balance, a Posting that contradicts an Account's Currency, a Position from another installation
 * — and serving a Balance that quietly leaves it out would be worse than serving none.
 */
final class Unappliable extends RuntimeException {

    Unappliable(String reason) {
        super(reason);
    }

    Unappliable(String reason, Throwable cause) {
        super(reason, cause);
    }
}
