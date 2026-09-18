package io.nostro.domain;

/**
 * A caller-supplied identifier, unique within a Tenant, under which an Entry is recorded at most
 * once. Presenting the same key again returns what the first attempt returned (ADR-0005).
 */
public record IdempotencyKey(String value) {

    public static final int MAX_LENGTH = 128;

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("idempotency key must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("idempotency key must be at most " + MAX_LENGTH + " characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
