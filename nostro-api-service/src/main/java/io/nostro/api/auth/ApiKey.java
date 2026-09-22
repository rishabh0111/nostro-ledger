package io.nostro.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * An opaque API key: 256 bits of randomness behind a prefix that tells the filter chain what it
 * is looking at. Only its SHA-256 is stored, which is enough for a value nobody chose; the key
 * itself is shown once, when issued.
 */
public record ApiKey(String value) {

    public static final String PREFIX = "nk_";

    private static final SecureRandom RANDOM = new SecureRandom();

    public ApiKey {
        if (!looksLike(value)) {
            throw new IllegalArgumentException("an API key starts with " + PREFIX);
        }
    }

    public static ApiKey generate() {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return new ApiKey(PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
    }

    public static boolean looksLike(String bearer) {
        return bearer.startsWith(PREFIX);
    }

    /** SHA-256 of the key, lower-case hex: what the api_key table stores. */
    public String hash() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.US_ASCII)));
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
