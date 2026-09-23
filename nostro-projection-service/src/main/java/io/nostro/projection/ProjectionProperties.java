package io.nostro.projection;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param kafka        the broker, the consumer group, and any consumer settings beyond the projection's own
 * @param retryBackoff how long to wait before retrying a message the database was unavailable for
 * @param balance      how the Balance service answers
 */
@ConfigurationProperties("nostro.projection")
record ProjectionProperties(Kafka kafka, @DefaultValue("1s") Duration retryBackoff, @DefaultValue Balance balance) {

    /**
     * @param consumer overrides for the consumer's configuration; the settings correctness rests on
     *                 are refused ({@link ConsumerSettings})
     */
    record Kafka(String bootstrapServers, @DefaultValue("nostro-projection") String groupId, Map<String, String> consumer) {

        Kafka {
            consumer = consumer == null ? Map.of() : Map.copyOf(consumer);
        }
    }

    /**
     * @param maxWait the longest a read with a minimum Position waits for the watermark, whatever the
     *                caller's deadline allows: the server's cap (ADR-0007). An estimate, to be replaced by
     *                one derived from measured lag (docs/research/ordering-and-watermarks.md section 7).
     */
    record Balance(@DefaultValue("1s") Duration maxWait) {
    }
}
