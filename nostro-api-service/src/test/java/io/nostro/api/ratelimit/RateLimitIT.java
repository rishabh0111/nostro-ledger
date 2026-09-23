package io.nostro.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.lettuce.core.RedisClient;
import io.nostro.api.LedgerIntegrationTest;
import io.nostro.api.auth.Permission;
import io.nostro.api.problem.ProblemType;
import io.nostro.api.ratelimit.TenantRateLimiter.Decision;
import io.nostro.testsupport.LedgerRedis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * The limit as a caller meets it: another API instance spends a Tenant's budget, and this one
 * refuses that Tenant's next request with a Problem Detail, like every other refusal.
 */
class RateLimitIT extends LedgerIntegrationTest {

    @Autowired
    RateLimitProperties limits;

    @Test
    @DisplayName("a Tenant whose budget another instance spent is throttled here: 429 rate-limited with Retry-After, while another Tenant is not")
    void aThrottledCallerGetsAProblemDetail() throws Exception {
        var tenant = newTenant();
        var key = newApiKey(tenant, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var account = openAccount(key, "cash");
        var neighbour = newTenant();
        var neighboursKey = newApiKey(neighbour, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var neighboursAccount = openAccount(neighboursKey, "cash");

        var redis = RedisClient.create(LedgerRedis.uri());
        try (var otherInstance = new TenantRateLimiter(redis, limits.capacity(), limits.refill(), limits.refillPeriod(), limits.timeout())) {
            while (otherInstance.tryAcquire(tenant) instanceof Decision.Allowed) {
                // the other instance's traffic, spending the Tenant's budget
            }
        } finally {
            redis.shutdown();
        }

        http.perform(get("/v1/accounts/{id}", account).header(HttpHeaders.AUTHORIZATION, bearer(key)))
                .andExpect(problem(ProblemType.RATE_LIMITED))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        http.perform(get("/v1/accounts/{id}", neighboursAccount).header(HttpHeaders.AUTHORIZATION, bearer(neighboursKey)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a request with no credential spends no one's budget: it is refused 401 before the limit is consulted")
    void anUnauthenticatedRequestSpendsNothing() throws Exception {
        http.perform(get("/v1/accounts/{id}", uuid()))
                .andExpect(problem(ProblemType.UNAUTHENTICATED));
        assertThat(ProblemType.RATE_LIMITED.status().value()).isEqualTo(429);
    }
}
