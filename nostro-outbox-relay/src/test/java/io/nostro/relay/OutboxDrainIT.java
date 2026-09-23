package io.nostro.relay;

import static io.nostro.relay.RelayFixture.begin;
import static io.nostro.relay.RelayFixture.newTenant;
import static io.nostro.relay.RelayFixture.publishSeq;
import static io.nostro.relay.RelayFixture.read;
import static io.nostro.relay.RelayFixture.record;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nostro.domain.TenantId;
import io.nostro.relay.RelayFixture.Written;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The drain, one batch at a time, against the real outbox and a real broker: what it publishes, in
 * which order, under which key, and what a crash between publishing and marking leaves behind.
 */
class OutboxDrainIT {

    private static final String TOPIC = "drain-" + UUID.randomUUID();

    private static Producer<String, String> producer;

    @BeforeAll
    static void topic() {
        try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, RelayFixture.KAFKA.getBootstrapServers()))) {
            new TopicSchema(TOPIC, 12, (short) 1).ensure(admin);
        }
        producer = new KafkaProducer<>(ProducerSettings.of(RelayFixture.KAFKA.getBootstrapServers(), Map.of()));
    }

    @AfterAll
    static void closeProducer() {
        producer.close();
    }

    @Test
    @DisplayName("every committed Entry is published once, keyed by its Tenant, and a Tenant's Entries arrive in the order they were recorded")
    void publishesInPositionOrderKeyedByTenant() throws SQLException {
        var a = newTenant();
        var b = newTenant();
        var written = new ArrayList<Written>();
        for (int i = 0; i < 5; i++) {
            written.add(record(a));
            written.add(record(b));
        }

        // Three to a batch, so the order has to survive batch boundaries as well as within one.
        drainAll(new OutboxDrain(producer, TOPIC, 3));

        var published = read(TOPIC, Set.of(a, b), 10);
        assertThat(published).hasSize(10);
        assertThat(published).allSatisfy(message ->
                assertThat(message.key()).isEqualTo(io.nostro.outbox.EntryRecorded.fromJson(message.value()).tenantId()));
        for (var tenant : List.of(a, b)) {
            var ofTenant = published.stream().filter(message -> message.key().equals(tenant.toString())).toList();
            assertThat(ofTenant).extracting(RelayFixture::entryOf)
                    .containsExactlyElementsOf(entriesOf(written, tenant));
            assertThat(ofTenant).extracting(ConsumerRecord::partition).containsOnly(ofTenant.getFirst().partition());
        }
        // publish_seq records the order published, which is the order recorded.
        assertThat(written).extracting(RelayFixture::publishSeq).doesNotContainNull().isSorted();
    }

    @Test
    @DisplayName("an Entry that committed late is not overtaken: nothing recorded after it is published until it commits, then it goes first")
    void aLateCommitterIsNotOvertaken() throws SQLException {
        var tenant = newTenant();
        var drain = new OutboxDrain(producer, TOPIC, 100);
        var before = record(tenant);

        Written late;
        Written after;
        try (var open = begin(tenant)) {
            late = open.written();
            after = record(tenant);

            drainAll(drain);

            // Committed before the open transaction began writing: nothing can appear beneath it.
            assertThat(publishSeq(before)).isNotNull();
            // Committed, but its Position is above one still in flight. Publishing it would let the
            // projection report a watermark past an Entry it has not applied.
            assertThat(publishSeq(after)).isNull();
            assertThat(publishSeq(late)).isNull();

            open.commit();
        }
        drainAll(drain);

        assertThat(read(TOPIC, Set.of(tenant), 3)).extracting(RelayFixture::entryOf)
                .containsExactly(before.entry().toString(), late.entry().toString(), after.entry().toString());
        assertThat(publishSeq(late)).isLessThan(publishSeq(after));
    }

    @Test
    @DisplayName("a crash between publishing and marking leaves the batch unmarked, and the next drain publishes it again rather than losing it")
    void aCrashBetweenPublishingAndMarkingRepublishes() throws SQLException {
        var tenant = newTenant();
        var written = List.of(record(tenant), record(tenant), record(tenant));
        var drain = new OutboxDrain(producer, TOPIC, 100);

        try (var connection = RelayFixture.relayConnection()) {
            assertThatThrownBy(() -> drain.drainOnce(connection, () -> {
                throw new IllegalStateException("the relay died here");
            })).hasMessage("the relay died here");
        }
        assertThat(written).extracting(RelayFixture::publishSeq).containsOnlyNulls();

        drainAll(drain);

        var published = read(TOPIC, Set.of(tenant), 6);
        var once = entriesOf(written, tenant);
        var twice = new ArrayList<>(once);
        twice.addAll(once);
        assertThat(published).extracting(RelayFixture::entryOf).containsExactlyElementsOf(twice);
        assertThat(written).extracting(RelayFixture::publishSeq).doesNotContainNull();
    }

    @Test
    @DisplayName("the relay's role reads the outbox of every Tenant and nothing else in the ledger")
    void theRelayRoleSeesTheOutboxAndNothingElse() throws SQLException {
        var tenant = newTenant();
        record(tenant);

        try (var connection = RelayFixture.relayConnection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT count(*) FROM outbox WHERE tenant_id = '" + tenant + "'")) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(1);
            }
            for (var table : List.of("entry", "posting", "account", "api_key")) {
                assertThatThrownBy(() -> statement.executeQuery("SELECT 1 FROM " + table + " LIMIT 1"))
                        .isInstanceOf(SQLException.class)
                        .satisfies(denied -> assertThat(((SQLException) denied).getSQLState()).isEqualTo("42501"));
            }
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE outbox SET payload = '{}' WHERE tenant_id = '" + tenant + "'"))
                    .satisfies(denied -> assertThat(((SQLException) denied).getSQLState()).isEqualTo("42501"));
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM outbox WHERE tenant_id = '" + tenant + "'"))
                    .satisfies(denied -> assertThat(((SQLException) denied).getSQLState()).isEqualTo("42501"));
        }
    }

    private static void drainAll(OutboxDrain drain) throws SQLException {
        try (var connection = RelayFixture.relayConnection()) {
            while (drain.drainOnce(connection) > 0) {
                // until nothing publishable is left
            }
        }
    }

    private static List<String> entriesOf(List<Written> written, TenantId tenant) {
        return written.stream().filter(w -> w.tenant().equals(tenant)).map(w -> w.entry().toString()).toList();
    }
}
