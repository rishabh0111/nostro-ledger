package io.nostro.relay;

import static io.nostro.relay.RelayFixture.newTenant;
import static io.nostro.relay.RelayFixture.owner;
import static io.nostro.relay.RelayFixture.read;
import static io.nostro.relay.RelayFixture.record;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.nostro.relay.RelayFixture.Written;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The relay as it runs: one leader, the others standing by, and what happens when the leader dies
 * mid-drain. The claim, at the relay's own seam: kill it mid-drain, restart, and
 * nothing is lost and nothing is reordered — at worst something is published twice, which is the
 * projection's to absorb.
 */
class OutboxRelayIT {

    private static final String TOPIC = "relay-" + UUID.randomUUID();
    private static final OutboxRelay.Timing FAST = new OutboxRelay.Timing(
            Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(50));

    private final List<OutboxRelay> relays = new ArrayList<>();
    private final List<Producer<String, String>> producers = new ArrayList<>();

    @BeforeAll
    static void topic() {
        try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, RelayFixture.KAFKA.getBootstrapServers()))) {
            new TopicSchema(TOPIC, 12, (short) 1).ensure(admin);
        }
    }

    @AfterEach
    void stopEverything() {
        relays.forEach(OutboxRelay::stop);
        producers.forEach(Producer::close);
    }

    @Test
    @DisplayName("while one relay leads, a second stands by and publishes nothing")
    void oneLeadsAndTheOtherStandsBy() {
        var first = relay(10);
        first.start();
        await().until(() -> first.state() == OutboxRelay.State.LEADING);

        var second = relay(10);
        second.start();
        await().until(() -> second.state() == OutboxRelay.State.STANDBY);

        assertThat(first.state()).isEqualTo(OutboxRelay.State.LEADING);
    }

    @Test
    @DisplayName("killed mid-drain and replaced, the relay loses no Entry and reorders none: a Tenant's Entries first arrive in the order recorded")
    void aKilledLeaderIsReplacedAndNothingIsLost() throws SQLException {
        var tenant = newTenant();
        var written = new ArrayList<Written>();
        for (int i = 0; i < 300; i++) {
            written.add(record(tenant));
        }

        // One row a batch, so the drain takes long enough to be killed in the middle of.
        var leader = relay(1);
        leader.start();
        await().until(() -> published(tenant) >= 20);
        killTheLeadersConnection();
        leader.stop();
        assertThat(published(tenant)).as("the leader was killed mid-drain").isLessThan(written.size());

        var replacement = relay(50);
        replacement.start();
        await().atMost(Duration.ofSeconds(30)).until(() -> published(tenant) == written.size());

        var arrived = read(TOPIC, Set.of(tenant), written.size());
        var firstArrivals = new LinkedHashSet<String>();
        arrived.forEach(message -> firstArrivals.add(RelayFixture.entryOf(message)));
        assertThat(List.copyOf(firstArrivals))
                .as("every Entry arrived, and in the order it was recorded")
                .containsExactlyElementsOf(written.stream().map(w -> w.entry().toString()).toList());
        assertThat(arrived.stream().collect(Collectors.groupingBy(RelayFixture::entryOf, Collectors.counting())).values())
                .as("a batch in flight when a relay died is published again, never more than that")
                .allSatisfy(copies -> assertThat(copies).isBetween(1L, 2L));
    }

    @Test
    @DisplayName("when the leader's connection is cut, the lead passes to the relay standing by, which drains what is left")
    void theStandbyTakesTheLead() {
        var first = relay(10);
        first.start();
        await().until(() -> first.state() == OutboxRelay.State.LEADING);
        var second = relay(10);
        second.start();
        await().until(() -> second.state() == OutboxRelay.State.STANDBY);

        first.stop();
        await().until(() -> second.state() == OutboxRelay.State.LEADING);

        var tenant = newTenant();
        var written = record(tenant);
        await().until(() -> RelayFixture.publishSeq(written) != null);
        assertThat(read(TOPIC, Set.of(tenant), 1)).extracting(RelayFixture::entryOf).containsExactly(written.entry().toString());
    }

    private OutboxRelay relay(int batchSize) {
        Producer<String, String> producer = new KafkaProducer<>(ProducerSettings.of(RelayFixture.KAFKA.getBootstrapServers(), Map.of()));
        producers.add(producer);
        var relay = new OutboxRelay(RelayFixture::relayConnection, new OutboxDrain(producer, TOPIC, batchSize), FAST);
        relays.add(relay);
        return relay;
    }

    private static long published(io.nostro.domain.TenantId tenant) {
        return owner().sql("SELECT count(*) FROM outbox WHERE tenant_id = ? AND published_at IS NOT NULL")
                .param(tenant.value())
                .query(Long.class)
                .single();
    }

    /** What a crash looks like from the database: the session holding the lead is gone, and the lock with it. */
    private static void killTheLeadersConnection() {
        int killed = owner().sql("""
                        SELECT count(pg_terminate_backend(pid)) FROM pg_locks
                         WHERE locktype = 'advisory' AND granted
                           AND ((classid::bigint << 32) | objid::bigint) = ?
                        """)
                .param(LeaderLock.KEY)
                .query(Integer.class)
                .single();
        assertThat(killed).isEqualTo(1);
    }
}
