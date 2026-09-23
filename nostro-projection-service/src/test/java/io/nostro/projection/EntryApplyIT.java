package io.nostro.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import io.nostro.domain.AccountId;
import io.nostro.domain.Position;
import io.nostro.domain.TenantId;
import io.nostro.outbox.EntryRecorded;
import io.nostro.outbox.EntryTopic;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Applying an Entry at most once, whatever the transport does, and halting rather than skipping
 * one that cannot be applied (ADR-0010).
 */
class EntryApplyIT extends ProjectionIntegrationTest {

    @Autowired
    MeterRegistry meters;

    @Test
    @DisplayName("every Entry published twice, then the partition replayed from zero: each Balance is the sum of its Postings, and the Tenant sums to zero throughout")
    void publishedTwiceAndReplayedFromZero() {
        var tenant = newTenant();
        var usd = List.of(AccountId.random(), AccountId.random(), AccountId.random());
        var eur = List.of(AccountId.random(), AccountId.random());
        var expected = new HashMap<AccountId, Long>();
        var entries = new ArrayList<EntryRecorded>();
        var random = new Random(20260923);
        for (int i = 0; i < 40; i++) {
            var postings = new ArrayList<EntryRecorded.PostingRecorded>();
            addPair(postings, expected, usd, random);
            if (i % 4 == 0) {
                // Two Currencies in one Entry: it must balance in each, and be applied whole.
                addPair(postings, expected, eur, random);
            }
            entries.add(entry(tenant, nextPosition(), postings));
        }
        double reordersBefore = meters.get("nostro.projection.reorders").counter().count();

        try (var sampler = new ZeroSumSampler(tenant)) {
            entries.forEach(ProjectionIntegrationTest::publish);
            entries.forEach(ProjectionIntegrationTest::publish);
            awaitWatermark(tenant, entries.getLast().createdAt());
            awaitCaughtUp(partitionOf(tenant));

            assertBalances(tenant, expected);
            assertThat(appliedEntries(tenant)).isEqualTo(entries.size());

            // Replay from zero: forget where the partition was, and start again from its first message.
            consumer.stop();
            asOwner().sql("DELETE FROM consumer_position WHERE topic = ? AND partition = ?")
                    .params(EntryTopic.NAME, partitionOf(tenant))
                    .update();
            consumer.start();
            awaitCaughtUp(partitionOf(tenant));

            assertBalances(tenant, expected);
            assertThat(appliedEntries(tenant)).isEqualTo(entries.size());
            assertThat(sampler.samples()).as("the sampler looked while Entries were applied").isGreaterThan(10);
            assertThat(sampler.violations()).as("moments the Tenant's Balances did not sum to zero").isEmpty();
        }
        assertThat(watermarkOf(tenant)).contains(entries.getLast().createdAt());
        assertThat(meters.get("nostro.projection.reorders").counter().count()).isEqualTo(reordersBefore);
    }

    /** A message the ledger could not have written, made for the Tenant and one of its USD Accounts. */
    interface Poison {
        String[] keyAndValue(TenantId tenant, AccountId usdAccount);
    }

    static Stream<Arguments> poison() {
        return Stream.of(
                Arguments.of("a message that is not an Entry", (Poison) (tenant, account) ->
                        new String[] {tenant.toString(), "{\"not\": \"an entry\""}),
                Arguments.of("an Entry that does not balance", (Poison) (tenant, account) -> keyed(
                        entry(tenant, nextPosition(), List.of(posting(account, "USD", -100), posting(AccountId.random(), "USD", 90))))),
                Arguments.of("an Entry keyed by another Tenant", (Poison) (tenant, account) -> new String[] {
                        newTenantOnThePoisonPartition().toString(), entry(tenant, account, AccountId.random(), "USD", 1).toJson()}),
                Arguments.of("a Posting in another Currency than its Account's", (Poison) (tenant, account) -> keyed(
                        entry(tenant, nextPosition(), List.of(posting(account, "EUR", -5), posting(AccountId.random(), "EUR", 5))))),
                Arguments.of("a Position from another ledger installation", (Poison) (tenant, account) -> keyed(
                        entry(tenant, new Position(INSTALLATION + 1, nextPosition().xid8()),
                                List.of(posting(account, "USD", -5), posting(AccountId.random(), "USD", 5))))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("poison")
    @DisplayName("a message that cannot be applied halts its partition — its Tenant's later Entries wait behind it, other partitions carry on, and the halt is a metric")
    void anUnappliableMessageHaltsItsPartition(String kind, Poison poison) {
        var tenant = newTenantOnThePoisonPartition();
        var cash = AccountId.random();
        var before = entry(tenant, AccountId.random(), cash, "USD", 1_000);
        publish(before);
        awaitWatermark(tenant, before.createdAt());
        double haltsBefore = meters.get("nostro.projection.halts").counter().count();

        var poisoned = poison.keyAndValue(tenant, cash);
        long poisonOffset = publish(poisoned[0], poisoned[1]).offset();
        var behind = entry(tenant, AccountId.random(), cash, "USD", 1);
        publish(behind);
        var bystander = newTenant();
        var elsewhere = entry(bystander, AccountId.random(), AccountId.random(), "USD", 7);
        publish(elsewhere);

        try {
            await().atMost(Duration.ofSeconds(30)).until(() -> consumer.halted().contains(poisonPartition()));
            awaitWatermark(bystander, elsewhere.createdAt());
            assertThat(meters.get("nostro.projection.partitions.halted").gauge().value()).isEqualTo(1.0);
            assertThat(meters.get("nostro.projection.halts").counter().count()).isEqualTo(haltsBefore + 1);

            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                    .until(() -> watermarkOf(tenant).orElseThrow().equals(before.createdAt()));
            assertThat(balanceOf(tenant, cash)).as("nothing behind the halt was applied").isEqualTo(1_000);
        } finally {
            // The remedy is a person's decision, and this is what they would do: move the stored
            // offset past the message, and restart the consumer.
            consumer.stop();
            asOwner().sql("UPDATE consumer_position SET next_offset = ? WHERE topic = ? AND partition = ?")
                    .params(poisonOffset + 1, EntryTopic.NAME, POISON_PARTITION)
                    .update();
            consumer.start();
        }
        awaitWatermark(tenant, behind.createdAt());
        assertThat(consumer.halted()).isEmpty();
        assertThat(meters.get("nostro.projection.partitions.halted").gauge().value()).isZero();
        assertThat(balanceOf(tenant, cash)).isEqualTo(1_001);
    }

    // -- helpers ---------------------------------------------------------------------------------

    private static String[] keyed(EntryRecorded entry) {
        return new String[] {entry.tenantId(), entry.toJson()};
    }

    private static TopicPartition poisonPartition() {
        return new TopicPartition(EntryTopic.NAME, POISON_PARTITION);
    }

    private static void addPair(List<EntryRecorded.PostingRecorded> postings, Map<AccountId, Long> expected,
                                List<AccountId> accounts, Random random) {
        var from = accounts.get(random.nextInt(accounts.size()));
        var to = accounts.get((accounts.indexOf(from) + 1 + random.nextInt(accounts.size() - 1)) % accounts.size());
        long minor = 1 + random.nextInt(10_000);
        var currency = accounts.size() == 3 ? "USD" : "EUR";
        postings.add(posting(from, currency, -minor));
        postings.add(posting(to, currency, minor));
        expected.merge(from, -minor, Long::sum);
        expected.merge(to, minor, Long::sum);
    }

    private void assertBalances(TenantId tenant, Map<AccountId, Long> expected) {
        expected.forEach((account, sum) ->
                assertThat(balanceOf(tenant, account)).as("Balance of %s", account).isEqualTo(sum));
    }

    private static long appliedEntries(TenantId tenant) {
        return asOwner().sql("SELECT count(*) FROM applied_entry WHERE tenant_id = ?").param(tenant.value()).query(Long.class).single();
    }

    /** Waits until the projection has stored an offset at the end of the partition: every message on it has been taken. */
    private static void awaitCaughtUp(int partition) {
        long end;
        try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            var topicPartition = new TopicPartition(EntryTopic.NAME, partition);
            end = admin.listOffsets(Map.of(topicPartition, OffsetSpec.latest())).partitionResult(topicPartition).get().offset();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> asOwner()
                .sql("SELECT next_offset FROM consumer_position WHERE topic = ? AND partition = ?")
                .params(EntryTopic.NAME, partition)
                .query(Long.class)
                .optional()
                .map(next -> next >= end)
                .orElse(false));
    }

    /**
     * Looks at the Tenant's Balances, as the owner, as often as it can while the test runs, and
     * records every moment they did not sum to zero in some Currency. One statement per look, so
     * each look is one snapshot.
     */
    private static final class ZeroSumSampler implements AutoCloseable {

        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicInteger samples = new AtomicInteger();
        private final List<String> violations = new CopyOnWriteArrayList<>();
        private final Thread thread;

        ZeroSumSampler(TenantId tenant) {
            // One connection for every look, not one per look.
            var owner = org.springframework.jdbc.core.simple.JdbcClient.create(new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true));
            thread = Thread.ofPlatform().name("zero-sum-sampler").start(() -> {
                while (running.get()) {
                    violations.addAll(owner.sql("""
                                    SELECT currency || ' ' || sum(amount_minor) FROM projected_balance
                                     WHERE tenant_id = ? GROUP BY currency HAVING sum(amount_minor) <> 0
                                    """)
                            .param(tenant.value())
                            .query(String.class)
                            .list());
                    samples.incrementAndGet();
                }
            });
        }

        int samples() {
            return samples.get();
        }

        List<String> violations() {
            return List.copyOf(violations);
        }

        @Override
        public void close() {
            running.set(false);
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
