package io.nostro.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.ConfigException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The producer's ordering protection cannot be turned off by accident
 * (docs/research/ordering-and-watermarks.md section 4). Idempotence is Kafka's default, and a
 * default is silently dropped the moment a conflicting setting appears; written out, the same
 * mistake is a {@link ConfigException} at startup.
 */
class ProducerSettingsTest {

    private static final String BROKER = "localhost:9092";

    @Test
    @DisplayName("idempotence, acks=all and at most five in flight are written out, not defaulted")
    void theOrderingProtectionIsExplicit() {
        var settings = ProducerSettings.of(BROKER, Map.of());

        assertThat(settings).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5");
    }

    @Test
    @DisplayName("tuning acks for latency is a ConfigException when the producer is built, not a silent downgrade")
    void aConflictingSettingFailsLoudly() {
        var settings = ProducerSettings.of(BROKER, Map.of(ProducerConfig.ACKS_CONFIG, "1"));

        assertThatThrownBy(() -> new KafkaProducer<>(settings).close())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("acks");
    }

    @Test
    @DisplayName("so is raising in-flight requests past what idempotence can order")
    void tooManyInFlightFailsLoudly() {
        var settings = ProducerSettings.of(BROKER, Map.of(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "6"));

        assertThatThrownBy(() -> new KafkaProducer<>(settings).close())
                .isInstanceOf(ConfigException.class);
    }

    @Test
    @DisplayName("idempotence itself cannot be overridden at all")
    void idempotenceCannotBeOverridden() {
        assertThatThrownBy(() -> ProducerSettings.of(BROKER, Map.of(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "false")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG);
    }

    @Test
    @DisplayName("a harmless override, such as linger, is applied")
    void aHarmlessOverrideIsApplied() {
        var settings = ProducerSettings.of(BROKER, Map.of(ProducerConfig.LINGER_MS_CONFIG, "20"));

        assertThat(settings).containsEntry(ProducerConfig.LINGER_MS_CONFIG, "20");
        new KafkaProducer<>(settings).close();
    }
}
