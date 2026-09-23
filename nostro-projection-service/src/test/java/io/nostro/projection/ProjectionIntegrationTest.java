package io.nostro.projection;

import static org.awaitility.Awaitility.await;

import io.nostro.domain.AccountId;
import io.nostro.domain.Position;
import io.nostro.domain.TenantId;
import io.nostro.outbox.EntryRecorded;
import io.nostro.outbox.EntryRecorded.PostingRecorded;
import io.nostro.outbox.EntryTopic;
import io.nostro.testsupport.LedgerKafka;
import io.nostro.testsupport.ProjectionPostgres;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The one Spring context the projection's tests share (ADR-0012): the real service, against its own
 * Postgres and the suite's broker, connected as its restricted role. Its seam is Kafka in: tests
 * publish {@link EntryRecorded} messages exactly as the relay does, keyed by Tenant onto the real
 * topic, and read back what the projection made of them.
 *
 * <p>One partition is set aside for messages that halt it, so that the tests that make it halt do
 * not stop anyone else's Entries, and the tests that replay a partition from zero never replay one
 * that holds a message nobody may apply.
 */
@SpringBootTest(properties = {
        "NOSTRO_PROJECTION_PASSWORD=projection-secret",
        "nostro.projection.retry-backoff=100ms"
})
public abstract class ProjectionIntegrationTest {

    static final PostgreSQLContainer POSTGRES = ProjectionPostgres.instance();
    static final KafkaContainer KAFKA = LedgerKafka.instance();

    /** The ledger installation every Position in these tests belongs to. */
    protected static final long INSTALLATION = 7_261_000_000_000_000_001L;

    /** The partition the halting tests use, and nobody else. */
    protected static final int POISON_PARTITION = EntryTopic.PARTITIONS - 1;

    private static final AtomicLong XID = new AtomicLong(1_000);

    private static final KafkaProducer<String, String> PRODUCER;

    static {
        try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(EntryTopic.NAME, EntryTopic.PARTITIONS, (short) 1))).all().get();
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException(e);
            }
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        PRODUCER = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true"));
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("nostro.projection.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    protected EntryConsumer consumer;

    @Autowired
    protected ProjectedBalances balances;

    @Autowired
    protected TenantTransactions transactions;

    /** The owner: this database's superuser, which bypasses every policy. For fixtures and inspection only. */
    protected static JdbcClient asOwner() {
        return JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    /** As the runtime role, outside the service: for the tests that ask what that role may do. */
    protected static JdbcClient asRuntimeRole() {
        return JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "nostro_projection", "projection-secret"));
    }

    /** A new Tenant whose key does not land on the partition set aside for halting. */
    protected static TenantId newTenant() {
        while (true) {
            var tenant = TenantId.random();
            if (partitionOf(tenant) != POISON_PARTITION) {
                return tenant;
            }
        }
    }

    /** A new Tenant whose key lands on the partition set aside for halting. */
    protected static TenantId newTenantOnThePoisonPartition() {
        while (true) {
            var tenant = TenantId.random();
            if (partitionOf(tenant) == POISON_PARTITION) {
                return tenant;
            }
        }
    }

    /** The partition Kafka's default partitioner puts a key on. */
    protected static int partitionOf(TenantId tenant) {
        return Utils.toPositive(Utils.murmur2(EntryTopic.key(tenant).getBytes(StandardCharsets.UTF_8))) % EntryTopic.PARTITIONS;
    }

    /** The next Position, higher than every one before it, as the ledger would assign them. */
    protected static Position nextPosition() {
        return new Position(INSTALLATION, XID.incrementAndGet());
    }

    protected static PostingRecorded posting(AccountId account, String currency, long minor) {
        return new PostingRecorded(UUID.randomUUID().toString(), account.toString(), currency, minor);
    }

    /** An Entry of the Tenant, at the next Position, moving {@code minor} of the currency from one Account to another. */
    protected static EntryRecorded entry(TenantId tenant, AccountId from, AccountId to, String currency, long minor) {
        return entry(tenant, nextPosition(), List.of(posting(from, currency, -minor), posting(to, currency, minor)));
    }

    protected static EntryRecorded entry(TenantId tenant, Position position, List<PostingRecorded> postings) {
        return new EntryRecorded(tenant.toString(), UUID.randomUUID().toString(), position.token(), null, null, postings);
    }

    /** Publishes the Entry exactly as the relay does: keyed by its Tenant, onto the Entries topic. */
    protected static RecordMetadata publish(EntryRecorded entry) {
        return publish(entry.tenantId(), entry.toJson());
    }

    protected static RecordMetadata publish(String key, String value) {
        try {
            return PRODUCER.send(new ProducerRecord<>(EntryTopic.NAME, key, value)).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Waits until the projection reports the Tenant at the Position, or beyond. */
    protected void awaitWatermark(TenantId tenant, Position position) {
        await().atMost(Duration.ofSeconds(30)).until(() -> watermarkOf(tenant)
                .map(watermark -> watermark.isAtLeast(position))
                .orElse(false));
    }

    protected java.util.Optional<Position> watermarkOf(TenantId tenant) {
        return balances.read(tenant, AccountId.random()).watermark();
    }

    /** The Tenant's Balance of the Account, as projected, read as the service reads it. */
    protected long balanceOf(TenantId tenant, AccountId account) {
        return balances.read(tenant, account).amountMinor();
    }
}
