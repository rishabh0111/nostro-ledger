package io.nostro.relay;

import io.nostro.domain.Position;
import io.nostro.domain.TenantId;
import io.nostro.outbox.EntryRecorded;
import io.nostro.outbox.EntryRecorded.PostingRecorded;
import io.nostro.testsupport.LedgerKafka;
import io.nostro.testsupport.LedgerPostgres;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The relay's seam, outbox in and Kafka out (ADR-0012): the suite's Postgres migrated the way the
 * migration container migrates it, the suite's broker, and the fixtures both sides need.
 *
 * <p>Entries are written here as the owner, straight into {@code entry} and {@code outbox}: the
 * relay reads nothing else, and the request path that writes them for real is the API service's to
 * test. What matters is that each row's Position is the xid8 of the transaction that wrote it,
 * which the schema's defaults guarantee whoever writes.
 */
final class RelayFixture {

    static final PostgreSQLContainer POSTGRES = LedgerPostgres.instance();
    static final KafkaContainer KAFKA = LedgerKafka.instance();
    static final String RELAY_PASSWORD = "relay-secret";

    static {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .placeholders(Map.of(
                        "app_password", "app-secret",
                        "control_password", "control-secret",
                        "relay_password", RELAY_PASSWORD))
                .load()
                .migrate();
    }

    private RelayFixture() {
    }

    static RelayProperties.Database relayDatabase() {
        return new RelayProperties.Database(POSTGRES.getJdbcUrl(), "nostro_relay", RELAY_PASSWORD);
    }

    /** A new dedicated connection as nostro_relay, exactly as the relay opens one. */
    static Connection relayConnection() throws SQLException {
        return RelayConfiguration.connect(relayDatabase());
    }

    static JdbcClient owner() {
        return JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    static TenantId newTenant() {
        var tenant = TenantId.random();
        owner().sql("INSERT INTO tenant (id, name) VALUES (?, ?)").params(tenant.value(), "tenant-" + tenant).update();
        return tenant;
    }

    /** An Entry and its outbox row as the ledger holds them, and the Position its transaction assigned. */
    record Written(TenantId tenant, UUID entry, long xid8) {
    }

    /** Writes and commits one Entry's outbox row. */
    static Written record(TenantId tenant) {
        try (var open = begin(tenant)) {
            return open.commit();
        }
    }

    /** Writes one Entry's outbox row in a transaction that stays open, holding its Position, until committed. */
    static OpenEntry begin(TenantId tenant) {
        try {
            var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            connection.setAutoCommit(false);
            var entry = UUID.randomUUID();
            long installation;
            long xid8;
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT system_identifier, pg_current_xact_id()::text FROM installation")) {
                result.next();
                installation = result.getLong(1);
                xid8 = Long.parseUnsignedLong(result.getString(2));
            }
            var payload = new EntryRecorded(tenant.toString(), entry.toString(), new Position(installation, xid8).token(), null, null,
                    List.of(new PostingRecorded(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "USD", -100),
                            new PostingRecorded(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "USD", 100)));
            try (var insertEntry = connection.prepareStatement("INSERT INTO entry (tenant_id, id) VALUES (?, ?)");
                 var insertOutbox = connection.prepareStatement(
                         "INSERT INTO outbox (tenant_id, id, entry_id, payload) VALUES (?, ?, ?, ?::jsonb)")) {
                insertEntry.setObject(1, tenant.value());
                insertEntry.setObject(2, entry);
                insertEntry.executeUpdate();
                insertOutbox.setObject(1, tenant.value());
                insertOutbox.setObject(2, UUID.randomUUID());
                insertOutbox.setObject(3, entry);
                insertOutbox.setString(4, payload.toJson());
                insertOutbox.executeUpdate();
            }
            return new OpenEntry(connection, new Written(tenant, entry, xid8));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static final class OpenEntry implements AutoCloseable {
        private final Connection connection;
        private final Written written;

        private OpenEntry(Connection connection, Written written) {
            this.connection = connection;
            this.written = written;
        }

        Written written() {
            return written;
        }

        Written commit() {
            try {
                connection.commit();
                return written;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Whether the relay has marked the Entry's outbox row published, and in which place. */
    static Long publishSeq(Written written) {
        return owner().sql("SELECT publish_seq FROM outbox WHERE tenant_id = ? AND entry_id = ?")
                .params(written.tenant().value(), written.entry())
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    /**
     * Every message on the topic whose key is one of the Tenants given, from the beginning, waiting
     * until at least {@code expected} have arrived and then a little longer, so that a duplicate
     * beyond the expected count is seen rather than missed.
     */
    static List<ConsumerRecord<String, String>> read(String topic, Set<TenantId> tenants, int expected) {
        Predicate<ConsumerRecord<String, String>> ours = record -> tenants.stream().anyMatch(t -> t.toString().equals(record.key()));
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"))) {
            var partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            var records = new ArrayList<ConsumerRecord<String, String>>();
            var deadline = Instant.now().plusSeconds(20);
            Instant settle = null;
            while (Instant.now().isBefore(deadline) && (settle == null || Instant.now().isBefore(settle))) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if (ours.test(record)) {
                        records.add(record);
                    }
                }
                if (settle == null && records.size() >= expected) {
                    settle = Instant.now().plusMillis(750);
                }
            }
            return records;
        }
    }

    static String positionOf(ConsumerRecord<String, String> record) {
        return EntryRecorded.fromJson(record.value()).position();
    }

    static String entryOf(ConsumerRecord<String, String> record) {
        return EntryRecorded.fromJson(record.value()).entryId();
    }
}
