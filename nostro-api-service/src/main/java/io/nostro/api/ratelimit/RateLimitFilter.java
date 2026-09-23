package io.nostro.api.ratelimit;

import io.nostro.api.problem.ProblemType;
import io.nostro.persistence.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spends one request of the bound Tenant's budget, or refuses with {@code 429 rate-limited} and
 * {@code Retry-After}, as Problem Details like every other refusal (ADR-0014).
 *
 * <p>It runs after the Tenant is bound, so it counts only requests that authenticated and were
 * authorised, against the Tenant their credential names. A request with no Tenant — the control
 * plane's — has no budget to spend.
 *
 * <p><b>If Redis cannot be asked, the request goes ahead.</b> A rate limit protects Tenants from each
 * other; a ledger that stopped recording Entries because its rate-limit store was down would turn
 * that protection into the outage. Every such request is logged here, and counted where metrics are.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final TenantRateLimiter limiter;
    private final JsonMapper json;
    private final Runnable uncheckedCounter;

    public RateLimitFilter(TenantRateLimiter limiter, JsonMapper json, Runnable uncheckedCounter) {
        this.limiter = limiter;
        this.json = json;
        this.uncheckedCounter = uncheckedCounter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var tenant = TenantContext.current();
        if (tenant.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        switch (limiter.tryAcquire(tenant.get())) {
            case TenantRateLimiter.Decision.Allowed allowed -> chain.doFilter(request, response);
            case TenantRateLimiter.Decision.Throttled throttled -> refuse(response, throttled.retryAfter());
            case TenantRateLimiter.Decision.Unchecked unchecked -> {
                uncheckedCounter.run();
                log.warn("rate limit for Tenant {} not checked: Redis did not answer", tenant.get(), unchecked.why());
                chain.doFilter(request, response);
            }
        }
    }

    private void refuse(HttpServletResponse response, Duration retryAfter) throws IOException {
        // Whole seconds, rounded up and at least one: the only unit Retry-After has.
        long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
        var problem = ProblemType.RATE_LIMITED.problem("this Tenant's request budget is spent; retry after " + seconds + "s");
        response.setStatus(problem.getStatus());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(problem));
    }
}
