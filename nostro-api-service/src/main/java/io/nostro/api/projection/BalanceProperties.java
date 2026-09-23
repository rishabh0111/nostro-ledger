package io.nostro.api.projection;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How the API service asks the projection for a Balance (ADR-0011): a deadline on each attempt and a
 * few retries on the statuses that mean "try again", and no circuit breaker.
 *
 * @param deadline     how long one attempt may take, end to end. Longer than the projection's cap on
 *                     waiting for a minimum Position, so that a waiting call ends with the
 *                     projection's honest answer rather than with this side giving up
 * @param attempts     calls made in all while the projection is {@code UNAVAILABLE} or out of time,
 *                     the first included
 * @param retryBackoff the pause before the second attempt, doubled before each after it
 */
@ConfigurationProperties("nostro.balance")
record BalanceProperties(
        @DefaultValue("2s") Duration deadline,
        @DefaultValue("3") int attempts,
        @DefaultValue("50ms") Duration retryBackoff) {

    BalanceProperties {
        if (attempts < 1) {
            throw new IllegalArgumentException("at least one attempt, configured " + attempts);
        }
    }
}
