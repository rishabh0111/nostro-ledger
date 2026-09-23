package io.nostro.relay;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * The relay's producer configuration, with its ordering protection written out.
 *
 * <p>{@code enable.idempotence} is already Kafka's default, and that is the trap: <em>"If
 * conflicting configurations are set and idempotence is not explicitly enabled, idempotence is
 * disabled"</em>. Someone setting {@code acks=1} for latency would turn off the protection against
 * reordering on retry with no error at all. Set explicitly, the same edit throws
 * {@code ConfigException} when the producer is built (docs/research/ordering-and-watermarks.md
 * section 4). Overrides are accepted for everything else, and refused for idempotence itself.
 *
 * <p>What idempotence does not do: deduplicate a batch republished after the relay crashed between
 * producing and marking. That comes from a new producer and is a new message; the projection's
 * {@code applied_entry} absorbs it (ADR-0010).
 */
final class ProducerSettings {

    private ProducerSettings() {
    }

    static Map<String, Object> of(String bootstrapServers, Map<String, String> overrides) {
        if (overrides.containsKey(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)) {
            throw new IllegalArgumentException(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG
                    + " is not configurable: the relay's ordering depends on it");
        }
        var settings = new HashMap<String, Object>(overrides);
        settings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        settings.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        settings.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        settings.put(ProducerConfig.CLIENT_ID_CONFIG, "nostro-outbox-relay");
        settings.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        settings.putIfAbsent(ProducerConfig.ACKS_CONFIG, "all");
        settings.putIfAbsent(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5");
        return Map.copyOf(settings);
    }
}
