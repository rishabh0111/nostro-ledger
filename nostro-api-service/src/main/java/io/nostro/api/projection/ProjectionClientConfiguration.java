package io.nostro.api.projection;

import io.nostro.balance.v1.BalanceServiceGrpc;
import io.nostro.domain.TenantId;
import io.nostro.grpc.TenantClientInterceptor;
import io.nostro.persistence.tenant.TenantContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GlobalClientInterceptor;
import org.springframework.grpc.client.GrpcChannelFactory;

/**
 * The API service's one remote call: to the projection, for a Balance (ADR-0011).
 *
 * <p>The Tenant rides on every call through a global client interceptor that reads it from
 * {@link TenantContext} — the Tenant the validated credential bound, never anything the request
 * carried — so no endpoint, and no future one, has to remember to send it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BalanceProperties.class)
class ProjectionClientConfiguration {

    static final String CHANNEL = "projection";

    @Bean
    @GlobalClientInterceptor
    TenantClientInterceptor tenantClientInterceptor() {
        return new TenantClientInterceptor(() -> TenantContext.current().map(TenantId::value));
    }

    /** On the channel {@code spring.grpc.client.channel.projection} names. */
    @Bean
    BalanceServiceGrpc.BalanceServiceBlockingStub projectionBalances(GrpcChannelFactory channels) {
        return BalanceServiceGrpc.newBlockingStub(channels.createChannel(CHANNEL));
    }
}
