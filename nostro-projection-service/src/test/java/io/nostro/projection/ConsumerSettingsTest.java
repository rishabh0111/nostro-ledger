package io.nostro.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** The two consumer settings the projection's correctness rests on are written out, and cannot be overridden. */
class ConsumerSettingsTest {

    @Test
    @DisplayName("offsets are never committed to Kafka, and only committed records are read")
    void theCorrectnessSettingsAreExplicit() {
        var settings = ConsumerSettings.of("localhost:9092", "nostro-projection", Map.of());

        assertThat(settings).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
                .containsEntry(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    }

    @ParameterizedTest
    @ValueSource(strings = {ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, ConsumerConfig.ISOLATION_LEVEL_CONFIG, ConsumerConfig.GROUP_ID_CONFIG})
    @DisplayName("an override of a setting correctness rests on is refused")
    void theyCannotBeOverridden(String setting) {
        assertThatThrownBy(() -> ConsumerSettings.of("localhost:9092", "nostro-projection", Map.of(setting, "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(setting);
    }

    @Test
    @DisplayName("a harmless override, such as the poll size, is applied")
    void aHarmlessOverrideIsApplied() {
        assertThat(ConsumerSettings.of("localhost:9092", "g", Map.of(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "50")))
                .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "50");
    }
}
