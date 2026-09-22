package io.nostro.api.auth;

import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.stereotype.Component;

/**
 * Staff: any bearer that is neither an API key nor the control key is taken to be a staff token. The token proves a
 * recent login; the staff user's row, re-read here, says what they may do and for which Tenant.
 */
@Component
class StaffTokenAuthenticationProvider implements AuthenticationProvider {

    private final StaffTokens tokens;
    private final Credentials credentials;

    StaffTokenAuthenticationProvider(StaffTokens tokens, Credentials credentials) {
        this.tokens = tokens;
        this.credentials = credentials;
    }

    @Override
    public @Nullable Authentication authenticate(Authentication authentication) {
        var bearer = (BearerCredential) authentication;
        if (ApiKey.looksLike(bearer.token()) || ControlKey.looksLike(bearer.token())) {
            return null;
        }
        try {
            return credentials.callerFor(tokens.subjectOf(bearer.token()))
                    .map(CallerAuthentication::new)
                    .orElseThrow(() -> new BadCredentialsException("staff user unknown or revoked"));
        } catch (JwtValidationException expiredOrEarly) {
            throw new CredentialsExpiredException("staff token is not valid now", expiredOrEarly);
        } catch (JwtException malformedOrForged) {
            throw new BadCredentialsException("staff token is not valid", malformedOrForged);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return BearerCredential.class.isAssignableFrom(authentication);
    }
}
