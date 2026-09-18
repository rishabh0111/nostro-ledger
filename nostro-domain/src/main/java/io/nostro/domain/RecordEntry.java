package io.nostro.domain;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Record a new Entry from the Postings given.
 *
 * @param idempotencyKey the key the Entry is recorded at most once under
 * @param postings       the Postings, in the order the caller gave them
 * @param description    free text, may be null
 */
public record RecordEntry(IdempotencyKey idempotencyKey, List<Posting> postings, String description)
        implements LedgerCommand {

    public RecordEntry {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        postings = List.copyOf(Objects.requireNonNull(postings, "postings"));
        if (postings.isEmpty()) {
            throw new IllegalArgumentException("an entry has at least one posting");
        }
    }

    @Override
    public String canonicalForm() {
        var joiner = new StringJoiner("\n", "record\n", "");
        joiner.add("description=" + LedgerCommand.quoted(description));
        for (Posting posting : postings) {
            joiner.add(posting.account() + " " + posting.amount().currency().code() + " " + posting.amount().minor());
        }
        return joiner.toString();
    }
}
