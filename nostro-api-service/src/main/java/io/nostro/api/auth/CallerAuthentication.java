package io.nostro.api.auth;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** A validated credential, as Spring Security carries it: the {@link Caller} is the principal. */
public final class CallerAuthentication extends AbstractAuthenticationToken {

    private final transient Caller caller;

    public CallerAuthentication(Caller caller) {
        super(caller.permissions().stream().map(p -> new SimpleGrantedAuthority(p.name())).toList());
        this.caller = caller;
        setAuthenticated(true);
    }

    public Caller caller() {
        return caller;
    }

    @Override
    public Object getPrincipal() {
        return caller;
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public String getName() {
        return caller.id().toString();
    }
}
