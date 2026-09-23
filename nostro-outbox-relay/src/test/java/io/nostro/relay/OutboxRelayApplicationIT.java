package io.nostro.relay;

import static io.nostro.relay.RelayFixture.newTenant;
import static io.nostro.relay.RelayFixture.read;
import static io.nostro.relay.RelayFixture.record;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import io.nostro.outbox.EntryTopic;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The deployable as configured: it verifies the Entries topic, takes the lead, and drains on its
 * own. The only Spring context in this module, and closed after this class, so that no running
 * relay contends with the ones the other tests build by hand.
 */
@SpringBootTest
@DirtiesContext
class OutboxRelayApplicationIT {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("nostro.relay.database.url", RelayFixture.POSTGRES::getJdbcUrl);
        registry.add("nostro.relay.database.password", () -> RelayFixture.RELAY_PASSWORD);
        registry.add("nostro.relay.kafka.bootstrap-servers", RelayFixture.KAFKA::getBootstrapServers);
        registry.add("nostro.relay.idle-poll", () -> "10ms");
    }

    @Autowired
    OutboxRelay relay;

    @Autowired
    MeterRegistry meters;

    @Test
    @DisplayName("started, the relay has made the Entries topic with its fixed partition count, leads, and publishes what is recorded, and says so in its metrics")
    void theDeployableDrainsOnItsOwn() throws Exception {
        try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, RelayFixture.KAFKA.getBootstrapServers()))) {
            var topic = admin.describeTopics(Set.of(EntryTopic.NAME)).allTopicNames().get().get(EntryTopic.NAME);
            assertThat(topic.partitions()).hasSize(EntryTopic.PARTITIONS);
        }
        await().until(() -> relay.state() == OutboxRelay.State.LEADING);

        var tenant = newTenant();
        var written = record(tenant);

        await().until(() -> RelayFixture.publishSeq(written) != null);
        var published = read(EntryTopic.NAME, Set.of(tenant), 1);
        assertThat(published).hasSize(1);
        assertThat(published.getFirst().key()).isEqualTo(EntryTopic.key(tenant));
        assertThat(meters.get("nostro.relay.leading").gauge().value()).isEqualTo(1.0);
        assertThat(meters.get("nostro.relay.published").counter().count()).isGreaterThanOrEqualTo(1.0);
        assertThat(meters.find("kafka.producer.record.send.total").meters()).as("the producer's own metrics").isNotEmpty();
    }
}
