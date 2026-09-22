package io.nostro.api.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param jwtSecret the HS256 key that signs staff tokens; at least 32 bytes, from the environment,
 *                  never from a file in the repository
 * @param jwtTtl    how long a staff token is good for; short, because a token cannot be revoked and
 *                  the staff user behind it can
 */
@ConfigurationProperties("nostro.auth")
public record AuthProperties(String jwtSecret, @DefaultValue("15m") Duration jwtTtl) {

    public AuthProperties {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("nostro.auth.jwt-secret must be at least 32 bytes");
        }
        if (jwtTtl.isNegative() || jwtTtl.isZero()) {
            throw new IllegalArgumentException("nostro.auth.jwt-ttl must be positive");
        }
    }

    public SecretKey signingKey() {
        return new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
