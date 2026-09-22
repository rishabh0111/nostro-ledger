package io.nostro.api.auth;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * The credential a request presented and nothing else: the value after {@code Bearer}, not yet
 * examined. Whether it is an API key or a staff token is for the providers to decide.
 */
public final class BearerCredential extends AbstractAuthenticationToken {

    private final transient String token;

    public BearerCredential(String token) {
        super(List.of());
        this.token = token;
        setAuthenticated(false);
    }

    public String token() {
        return token;
    }

    @Override
    public Object getPrincipal() {
        return "";
    }

    @Override
    public Object getCredentials() {
        return token;
    }
}
