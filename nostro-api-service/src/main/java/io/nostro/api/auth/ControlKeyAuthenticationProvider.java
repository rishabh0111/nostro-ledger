package io.nostro.api.auth;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * The control plane: a bearer that looks like a control key is the configured one, or it is
 * nothing. The Caller it yields acts for no Tenant and holds {@link Permission#CONTROL} alone, so
 * it can reach no ledger endpoint (ADR-0015).
 */
@Component
class ControlKeyAuthenticationProvider implements AuthenticationProvider {

    /** There is one bootstrap credential and no row for it; this names it wherever a Caller id is shown. */
    static final UUID CONTROL_CALLER = UUID.nameUUIDFromBytes("nostro-control".getBytes(StandardCharsets.UTF_8));

    private final ControlKey key;

    ControlKeyAuthenticationProvider(AuthProperties properties) {
        this.key = properties.controlKey();
    }

    @Override
    public @Nullable Authentication authenticate(Authentication authentication) {
        var bearer = (BearerCredential) authentication;
        if (!ControlKey.looksLike(bearer.token())) {
            return null;
        }
        if (!key.matches(bearer.token())) {
            throw new BadCredentialsException("unknown control key");
        }
        return new CallerAuthentication(new Caller(Caller.Kind.CONTROL, CONTROL_CALLER, null, Set.of(Permission.CONTROL)));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return BearerCredential.class.isAssignableFrom(authentication);
    }
}
