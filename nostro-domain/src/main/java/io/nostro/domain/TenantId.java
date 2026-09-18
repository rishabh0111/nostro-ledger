package io.nostro.domain;

import java.util.Objects;
import java.util.UUID;

/** An isolated owner of ledger data. Nothing is ever visible or referenceable across a Tenant boundary. */
public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "value");
    }

    public static TenantId random() {
        return new TenantId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
