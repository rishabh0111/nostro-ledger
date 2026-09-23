package io.nostro.api.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.nostro.domain.TenantId;
import java.time.Duration;

/**
 * Each Tenant's request budget, held in Redis so that every API instance draws on the same one.
 *
 * <p>This is the one piece of this project that is cross-cutting infrastructure rather than an
 * answer to a need of the ledger, and it is labelled as such (ADR-0008): rate limiting across
 * several API instances cannot be done in any one of them. What makes it the least generic version
 * available is the key — per Tenant, because a noisy neighbour is a Tenant, and Tenant isolation is
 * this project's subject.
 *
 * <p>Redis holds these buckets and nothing else: not Balances, not Idempotency Keys (ADR-0008 says
 * why each would be wrong). A bucket expires once it would have refilled to full anyway, so an idle
 * Tenant costs nothing.
 *
 * <p>A token bucket, refilled greedily: a Tenant may burst to {@code capacity} and sustains
 * {@code refill} per {@code refillPeriod}. Plain Java over Bucket4j's core and its Lettuce
 * integration; the Spring Boot starter for it is stale and unverified on Boot 4.1.
 */
public final class TenantRateLimiter implements AutoCloseable {

    /** What the limiter decided for one request. */
    public sealed interface Decision {
        record Allowed(long remaining) implements Decision {
        }

        record Throttled(Duration retryAfter) implements Decision {
        }

        /** Redis could not be asked in time; the request goes ahead and the caller counts it (see {@link RateLimitFilter}). */
        record Unchecked(RuntimeException why) implements Decision {
        }
    }

    static final String KEY_PREFIX = "nostro:rate:";

    private final StatefulRedisConnection<String, byte[]> connection;
    private final ProxyManager<String> buckets;
    private final BucketConfiguration budget;

    public TenantRateLimiter(RedisClient redis, long capacity, long refill, Duration refillPeriod, Duration timeout) {
        this.connection = redis.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
        this.buckets = Bucket4jLettuce.casBasedBuilder(connection)
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(10)))
                .requestTimeout(timeout)
                .build();
        this.budget = BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(refill, refillPeriod))
                .build();
    }

    /** Takes one request from the Tenant's budget, or says how long until there is one to take. */
    public Decision tryAcquire(TenantId tenant) {
        try {
            var probe = buckets.getProxy(KEY_PREFIX + tenant, () -> budget).tryConsumeAndReturnRemaining(1);
            return probe.isConsumed()
                    ? new Decision.Allowed(probe.getRemainingTokens())
                    : new Decision.Throttled(Duration.ofNanos(probe.getNanosToWaitForRefill()));
        } catch (RuntimeException redisDidNotAnswer) {
            return new Decision.Unchecked(redisDidNotAnswer);
        }
    }

    @Override
    public void close() {
        connection.close();
    }
}
