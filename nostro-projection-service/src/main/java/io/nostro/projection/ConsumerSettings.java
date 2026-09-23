package io.nostro.projection;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * The projection's consumer configuration, with the two settings its correctness rests on written
 * out and closed to overrides (docs/research/ordering-and-watermarks.md section 6).
 *
 * <p>{@code enable.auto.commit} defaults to true, which commits offsets to Kafka on a timer with
 * no relation to whether the Balance was written. The projection keeps its offsets in its own
 * database, in the apply transaction, and never reads Kafka's. {@code isolation.level} defaults to
 * {@code read_uncommitted}; were the relay ever to produce transactionally, that would apply
 * records from aborted transactions. Neither default is wrong today, and neither may become so by
 * someone's tuning.
 */
final class ConsumerSettings {

    private static final Set<String> FIXED = Set.of(
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, ConsumerConfig.ISOLATION_LEVEL_CONFIG, ConsumerConfig.GROUP_ID_CONFIG);

    private ConsumerSettings() {
    }

    static Map<String, Object> of(String bootstrapServers, String groupId, Map<String, String> overrides) {
        for (String fixed : FIXED) {
            if (overrides.containsKey(fixed)) {
                throw new IllegalArgumentException(fixed + " is not configurable: the projection's correctness depends on it");
            }
        }
        var settings = new HashMap<String, Object>(overrides);
        settings.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        settings.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        settings.put(ConsumerConfig.CLIENT_ID_CONFIG, "nostro-projection");
        settings.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        settings.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        settings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        settings.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        // Only reached for a partition the projection has never stored an offset for.
        settings.putIfAbsent(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return Map.copyOf(settings);
    }
}
