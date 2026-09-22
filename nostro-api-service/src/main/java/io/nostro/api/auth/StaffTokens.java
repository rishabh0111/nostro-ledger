package io.nostro.api.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Short-lived JWTs for staff (ADR-0006). A token is proof of a recent login and nothing more: it
 * names the staff user and when it expires. Permissions and the Tenant are read from the staff
 * user's row on every request, so revoking the user ends the token's usefulness immediately.
 */
@Component
public class StaffTokens {

    static final String ISSUER = "nostro";

    private final AuthProperties properties;
    private final Clock clock;
    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;

    StaffTokens(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.encoder = NimbusJwtEncoder.withSecretKey(properties.signingKey()).algorithm(MacAlgorithm.HS256).build();
        this.decoder = NimbusJwtDecoder.withSecretKey(properties.signingKey()).macAlgorithm(MacAlgorithm.HS256).build();
        var timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        this.decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(timestamps, new JwtIssuerValidator(ISSUER)));
    }

    public Issued issue(UUID staffUser) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.jwtTtl());
        var claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(staffUser.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        var token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims));
        return new Issued(token.getTokenValue(), expiresAt);
    }

    /** The staff user a valid token names; a token that is malformed, forged or expired is a {@link JwtException}. */
    public UUID subjectOf(String token) {
        var jwt = decoder.decode(token);
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new JwtException("token subject is not a staff user id");
        }
    }

    public record Issued(String token, Instant expiresAt) {
    }
}
