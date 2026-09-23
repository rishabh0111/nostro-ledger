package io.nostro.projection;

import io.micrometer.core.instrument.MeterRegistry;
import io.nostro.grpc.TenantServerInterceptor;
import io.nostro.outbox.EntryTopic;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerExecutorProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class ProjectionConfiguration {

    /**
     * Calls run on virtual threads, so a read parked on a lagging watermark costs almost nothing.
     * Boot's gRPC server does not follow {@code spring.threads.virtual.enabled}; without this it runs
     * calls on a cached pool of platform threads, one each, however long they are parked.
     */
    @Bean
    GrpcServerExecutorProvider virtualThreadCalls() {
        var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("grpc-call-", 0).factory());
        return () -> executor;
    }

    /** Not global: it wraps the Balance service alone, so the health service answers without a Tenant. */
    @Bean
    TenantServerInterceptor tenantServerInterceptor() {
        return new TenantServerInterceptor();
    }

    /** A new Kafka consumer each time the loop (re)connects; the loop's thread is the only one that touches it. */
    @Bean
    EntryConsumer entryConsumer(ProjectionProperties properties, EntryApplier applier, JdbcClient jdbc, MeterRegistry meters) {
        var kafka = properties.kafka();
        return new EntryConsumer(
                () -> new KafkaConsumer<>(ConsumerSettings.of(kafka.bootstrapServers(), kafka.groupId(), kafka.consumer())),
                EntryTopic.NAME, applier, jdbc, properties.retryBackoff(), meters);
    }
}
