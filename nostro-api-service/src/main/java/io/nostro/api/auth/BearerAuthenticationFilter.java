package io.nostro.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads {@code Authorization: Bearer ...} and hands the value to the two providers. A request
 * without the header passes through unauthenticated, to be refused by the chain's
 * {@code anyRequest().authenticated()}; a request whose credential fails is answered here and goes
 * no further. The Tenant is never read from anywhere else in the request (ADR-0006).
 */
final class BearerAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final AuthenticationManager authenticationManager;
    private final AuthenticationEntryPoint entryPoint;

    BearerAuthenticationFilter(AuthenticationManager authenticationManager, AuthenticationEntryPoint entryPoint) {
        this.authenticationManager = authenticationManager;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring(BEARER.length()).strip();
        try {
            var authenticated = authenticationManager.authenticate(new BearerCredential(token));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authenticated);
            SecurityContextHolder.setContext(context);
        } catch (AuthenticationException refused) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, refused);
            return;
        }
        chain.doFilter(request, response);
    }
}
