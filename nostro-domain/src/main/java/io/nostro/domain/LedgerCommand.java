package io.nostro.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A request to record an Entry, presented under an Idempotency Key.
 *
 * <p>The {@link #fingerprint()} is a digest of everything <em>except</em> the key: it is what the
 * key was presented with, stored alongside the key so that the same key with a different request
 * is a loud failure rather than a silent replay (ADR-0005).
 */
public sealed interface LedgerCommand permits RecordEntry, ReverseEntry {

    IdempotencyKey idempotencyKey();

    /** Free text supplied by the caller; may be null. */
    String description();

    /** SHA-256 over the canonical form, hex encoded. */
    default String fingerprint() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalForm().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }

    /** A deterministic rendering of the request, unambiguous across command kinds and field values. */
    String canonicalForm();

    static String quoted(String description) {
        return description == null ? "" : "\"" + description + "\"";
    }
}
