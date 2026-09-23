package io.nostro.api.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param redisUri     where the budgets live, e.g. {@code redis://redis:6379}
 * @param capacity     the most requests a Tenant may make in a burst
 * @param refill       how many requests a Tenant's budget regains every {@code refillPeriod}
 * @param refillPeriod the period {@code refill} is over; together they are the rate a Tenant may sustain
 * @param timeout      how long to wait for Redis before letting the request through unchecked
 */
@ConfigurationProperties("nostro.rate-limit")
public record RateLimitProperties(
        String redisUri,
        @DefaultValue("200") long capacity,
        @DefaultValue("100") long refill,
        @DefaultValue("1s") Duration refillPeriod,
        @DefaultValue("100ms") Duration timeout) {
}
