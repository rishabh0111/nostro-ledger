package io.nostro.projection;

import io.micrometer.core.instrument.MeterRegistry;
import io.nostro.outbox.EntryTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class ProjectionConfiguration {

    /** A new Kafka consumer each time the loop (re)connects; the loop's thread is the only one that touches it. */
    @Bean
    EntryConsumer entryConsumer(ProjectionProperties properties, EntryApplier applier, JdbcClient jdbc, MeterRegistry meters) {
        var kafka = properties.kafka();
        return new EntryConsumer(
                () -> new KafkaConsumer<>(ConsumerSettings.of(kafka.bootstrapServers(), kafka.groupId(), kafka.consumer())),
                EntryTopic.NAME, applier, jdbc, properties.retryBackoff(), meters);
    }
}
