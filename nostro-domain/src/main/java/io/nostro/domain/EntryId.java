package io.nostro.domain;

import java.util.Objects;
import java.util.UUID;

/** Identifies an Entry within its Tenant. */
public record EntryId(UUID value) {

    public EntryId {
        Objects.requireNonNull(value, "value");
    }

    public static EntryId random() {
        return new EntryId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
