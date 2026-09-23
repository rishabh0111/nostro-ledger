package io.nostro.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisClient;
import io.nostro.api.ratelimit.TenantRateLimiter.Decision;
import io.nostro.domain.TenantId;
import io.nostro.testsupport.LedgerRedis;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * The limiter against a real Redis, with no Spring: two limiters, each on its own client and
 * connection, are two API instances. The budget they draw on is the Tenant's, not theirs.
 */
class TenantRateLimiterTest {

    private static final int CAPACITY = 10;

    private final List<AutoCloseable> opened = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (var closeable : opened.reversed()) {
            closeable.close();
        }
    }

    @Test
    @DisplayName("two API instances share one Tenant's budget: ten requests between them, and the eleventh is throttled on either")
    void twoInstancesShareOneBudget() {
        var first = instance(LedgerRedis.uri());
        var second = instance(LedgerRedis.uri());
        var tenant = TenantId.random();

        for (int i = 0; i < CAPACITY; i++) {
            assertThat((i % 2 == 0 ? first : second).tryAcquire(tenant)).isInstanceOf(Decision.Allowed.class);
        }

        assertThat(first.tryAcquire(tenant)).isInstanceOf(Decision.Throttled.class);
        assertThat(second.tryAcquire(tenant)).isInstanceOfSatisfying(Decision.Throttled.class, throttled ->
                assertThat(throttled.retryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("one Tenant spending its budget costs another Tenant nothing: the noisy neighbour is contained")
    void anotherTenantsBudgetIsItsOwn() {
        var limiter = instance(LedgerRedis.uri());
        var noisy = TenantId.random();
        var quiet = TenantId.random();
        while (limiter.tryAcquire(noisy) instanceof Decision.Allowed) {
            // spend it all
        }

        assertThat(limiter.tryAcquire(quiet)).isInstanceOfSatisfying(Decision.Allowed.class, allowed ->
                assertThat(allowed.remaining()).isEqualTo(CAPACITY - 1));
    }

    @Test
    @DisplayName("with Redis gone, a request is let through unchecked rather than refused: a rate limit must not become the outage")
    void aRedisThatDoesNotAnswerIsUnchecked() {
        try (var doomed = new GenericContainer<>(LedgerRedis.IMAGE).withExposedPorts(6379)) {
            doomed.start();
            var limiter = instance("redis://" + doomed.getHost() + ":" + doomed.getMappedPort(6379));
            var tenant = TenantId.random();
            assertThat(limiter.tryAcquire(tenant)).isInstanceOf(Decision.Allowed.class);

            doomed.stop();

            assertThat(limiter.tryAcquire(tenant)).isInstanceOf(Decision.Unchecked.class);
        }
    }

    private TenantRateLimiter instance(String uri) {
        var client = RedisClient.create(uri);
        opened.add(client::shutdown);
        var limiter = new TenantRateLimiter(client, CAPACITY, 1, Duration.ofHours(1), Duration.ofMillis(200));
        opened.add(limiter);
        return limiter;
    }
}
