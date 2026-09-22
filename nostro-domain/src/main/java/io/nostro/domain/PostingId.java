package io.nostro.domain;

import java.util.Objects;
import java.util.UUID;

/** Identifies a Posting within its Tenant. Assigned when its Entry is recorded; a Posting never exists before that. */
public record PostingId(UUID value) {

    public PostingId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
