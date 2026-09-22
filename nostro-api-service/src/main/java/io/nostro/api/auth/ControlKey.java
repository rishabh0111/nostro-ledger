package io.nostro.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The control plane's bootstrap credential (ADR-0015). It comes from the environment, not from a
 * row: it is what creates the first Tenant, so there is nothing yet for it to be stored beside,
 * and {@code api_key.tenant_id} stays {@code NOT NULL}. A distinct prefix tells the filter chain
 * what it is looking at, as {@link ApiKey#PREFIX} does for ledger keys.
 */
public record ControlKey(String value) {

    public static final String PREFIX = "nc_";

    /** The secret after the prefix is at least this many bytes, as the staff signing secret is. */
    static final int MIN_SECRET_BYTES = 32;

    public ControlKey {
        if (!looksLike(value)) {
            throw new IllegalArgumentException("a control key starts with " + PREFIX);
        }
        if (value.substring(PREFIX.length()).getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("a control key carries at least " + MIN_SECRET_BYTES + " bytes after " + PREFIX);
        }
    }

    public static boolean looksLike(String bearer) {
        return bearer.startsWith(PREFIX);
    }

    /** Whether the bearer presented is this key, compared in constant time over digests so neither content nor length leaks. */
    public boolean matches(String bearer) {
        return MessageDigest.isEqual(sha256(value), sha256(bearer));
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required algorithm on every JVM", e);
        }
    }

    /** Never the key. */
    @Override
    public String toString() {
        return PREFIX + "...";
    }
}
