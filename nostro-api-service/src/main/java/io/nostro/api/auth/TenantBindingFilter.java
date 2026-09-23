package io.nostro.api.auth;

import io.nostro.domain.TenantId;
import io.nostro.persistence.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the authenticated Caller's Tenant to the request thread for the rest of the request, and
 * unbinds it when the request ends. This is the one place in main code that calls
 * {@link TenantContext#bind}; the transaction hook reads what is bound here and nothing else.
 * It is also where the Tenant goes onto the request's log lines, under
 * {@value #MDC_TENANT}. A Caller without a Tenant -- the control plane's -- binds nothing, and
 * every tenant-scoped query it might make sees no rows.
 */
final class TenantBindingFilter extends OncePerRequestFilter {

    /** The MDC key every log line of the request carries the Tenant under, beside the trace that correlates them. */
    static final String MDC_TENANT = "tenant";

    @Override
    @SuppressWarnings("try")
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var tenant = SecurityContextHolder.getContext().getAuthentication() instanceof CallerAuthentication authenticated
                ? authenticated.caller().tenant()
                : Optional.<TenantId>empty();
        if (tenant.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        try (var bound = TenantContext.bind(tenant.get());
             var logged = MDC.putCloseable(MDC_TENANT, tenant.get().toString())) {
            chain.doFilter(request, response);
        }
    }
}
