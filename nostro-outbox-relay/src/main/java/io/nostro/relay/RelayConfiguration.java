package io.nostro.relay;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics;
import io.nostro.outbox.EntryTopic;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RelayConfiguration {

    /** Closed after the relay has stopped: lifecycle beans stop before singletons are destroyed. */
    @Bean(destroyMethod = "close")
    Producer<String, String> entryProducer(RelayProperties properties) {
        return new KafkaProducer<>(ProducerSettings.of(properties.kafka().bootstrapServers(), properties.kafka().producer()));
    }

    /** The producer's own metrics — send rates, errors, retries, request latency — beside the relay's. */
    @Bean(destroyMethod = "close")
    KafkaClientMetrics entryProducerMetrics(Producer<String, String> entryProducer, MeterRegistry meters) {
        var metrics = new KafkaClientMetrics(entryProducer);
        metrics.bindTo(meters);
        return metrics;
    }

    /**
     * The relay, built only once the topic has been verified: a relay that cannot confirm the
     * partition count does not start, so it never publishes into the wrong layout.
     */
    @Bean
    OutboxRelay outboxRelay(RelayProperties properties, Producer<String, String> entryProducer, MeterRegistry meters) {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, properties.kafka().bootstrapServers()))) {
            new TopicSchema(EntryTopic.NAME, EntryTopic.PARTITIONS, properties.kafka().replicationFactor()).ensure(admin);
        }
        var drain = new OutboxDrain(entryProducer, EntryTopic.NAME, properties.batchSize());
        return new OutboxRelay(() -> connect(properties.database()), drain, properties.timing(), meters);
    }

    /**
     * A direct connection, never a pooled one: it carries the session-level advisory lock, which a
     * pool would hand to someone else or a transaction-mode pooler would not keep at all.
     */
    static Connection connect(RelayProperties.Database database) throws SQLException {
        var settings = new Properties();
        settings.setProperty("user", database.username());
        settings.setProperty("password", database.password());
        settings.setProperty("ApplicationName", "nostro-relay");
        settings.setProperty("tcpKeepAlive", "true");
        return DriverManager.getConnection(database.url(), settings);
    }
}
