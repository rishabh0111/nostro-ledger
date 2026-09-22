package io.nostro.api.auth;

import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Machine callers: a bearer that looks like an API key is one, or it is nothing. */
@Component
class ApiKeyAuthenticationProvider implements AuthenticationProvider {

    private final Credentials credentials;

    ApiKeyAuthenticationProvider(Credentials credentials) {
        this.credentials = credentials;
    }

    @Override
    public @Nullable Authentication authenticate(Authentication authentication) {
        var bearer = (BearerCredential) authentication;
        if (!ApiKey.looksLike(bearer.token())) {
            return null;
        }
        return credentials.callerFor(new ApiKey(bearer.token()))
                .map(CallerAuthentication::new)
                .orElseThrow(() -> new BadCredentialsException("unknown or revoked API key"));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return BearerCredential.class.isAssignableFrom(authentication);
    }
}
