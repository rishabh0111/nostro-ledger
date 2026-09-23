package io.nostro.relay;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param database       the ledger, as the relay's own role, over a direct connection
 * @param kafka          the broker, and any producer settings beyond the relay's own
 * @param batchSize      outbox rows per drain: per batch, one SELECT, one flush, one UPDATE batch
 * @param idlePoll       how long to wait after a drain found nothing publishable
 * @param standbyRetry   how often a relay that does not lead tries to
 * @param failureBackoff how long to wait after a failure before reconnecting
 */
@ConfigurationProperties("nostro.relay")
record RelayProperties(
        Database database,
        Kafka kafka,
        @DefaultValue("500") int batchSize,
        @DefaultValue("50ms") Duration idlePoll,
        @DefaultValue("1s") Duration standbyRetry,
        @DefaultValue("1s") Duration failureBackoff) {

    record Database(String url, String username, String password) {
    }

    /**
     * @param producer overrides for the producer's configuration; {@code enable.idempotence} is refused
     *                 ({@link ProducerSettings})
     */
    record Kafka(String bootstrapServers, @DefaultValue("1") short replicationFactor, Map<String, String> producer) {

        Kafka {
            producer = producer == null ? Map.of() : Map.copyOf(producer);
        }
    }

    OutboxRelay.Timing timing() {
        return new OutboxRelay.Timing(idlePoll, standbyRetry, failureBackoff);
    }
}
