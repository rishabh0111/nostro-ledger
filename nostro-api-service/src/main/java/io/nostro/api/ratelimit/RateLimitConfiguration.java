package io.nostro.api.ratelimit;

import io.lettuce.core.RedisClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfiguration {

    @Bean(destroyMethod = "shutdown")
    RedisClient rateLimitRedis(RateLimitProperties properties) {
        return RedisClient.create(properties.redisUri());
    }

    @Bean(destroyMethod = "close")
    TenantRateLimiter tenantRateLimiter(RedisClient rateLimitRedis, RateLimitProperties properties) {
        return new TenantRateLimiter(rateLimitRedis, properties.capacity(), properties.refill(), properties.refillPeriod(), properties.timeout());
    }
}
