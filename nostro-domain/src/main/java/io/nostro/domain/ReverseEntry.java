package io.nostro.domain;

import java.util.Objects;

/**
 * Record a Reversing Entry: an ordinary Entry whose Postings are the negation of the one named.
 * Subject to every rule an Entry is subject to, including a Constrained Account's floor, so a
 * correction can be refused (ADR-0004).
 *
 * @param idempotencyKey the key the Reversing Entry is recorded at most once under
 * @param reverses       the Entry to undo
 * @param description    free text, may be null
 */
public record ReverseEntry(IdempotencyKey idempotencyKey, EntryId reverses, String description)
        implements LedgerCommand {

    public ReverseEntry {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(reverses, "reverses");
    }

    @Override
    public String canonicalForm() {
        return "reverse\ndescription=" + LedgerCommand.quoted(description) + "\n" + reverses;
    }
}
