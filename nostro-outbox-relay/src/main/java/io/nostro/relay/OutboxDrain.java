package io.nostro.relay;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

/**
 * The ordering authority: one batch of the outbox, published in Position order and then marked.
 *
 * <p><b>By predicate, never by cursor.</b> The drain selects {@code WHERE published_at IS NULL}
 * rather than "after the last one published", because a cursor can step past a row whose
 * transaction had not yet committed and never return to it — a lost Entry with no error anywhere
 * (docs/research/ordering-and-watermarks.md section 1). A row that commits late is simply still
 * unpublished at the next poll.
 *
 * <p><b>Gated on the snapshot's {@code xmin}.</b> A Position is assigned at an Entry's first write,
 * not at its commit, so a late committer's Position can be lower than ones already published. Were
 * the relay to publish past it, the projection would report a watermark above an Entry it has not
 * applied, and a caller holding that Entry's Position would be told the Balance includes it. So a
 * row is published only once its Position is below {@code pg_snapshot_xmin} of the drain's own
 * snapshot: every transaction below that is either committed and visible or rolled back, so
 * nothing can later appear beneath what has been published (section 2). The two conditions
 * together make publish order exactly Position order. The cost is head-of-line blocking behind the
 * oldest open write transaction, bounded by the request path's {@code idle_in_transaction_session_timeout};
 * that is lag, disclosed by the Position, never a lie.
 *
 * <p><b>Publish, then mark, and a crash between them publishes again.</b> Kafka and Postgres share
 * no transaction, so there is no ordering of the two writes that avoids a duplicate after a crash.
 * The projection's {@code applied_entry} absorbs it (ADR-0010); the producer's idempotence does
 * not, because a republish after a restart is a new producer's new message.
 *
 * <p>Correct only under a single writer: two drains interleave their publishes and the order is
 * the network's. {@link LeaderLock} is what makes this the only one.
 */
final class OutboxDrain {

    /**
     * The sort is on {@code o.position}, qualified, and the text form is aliased away from it:
     * a bare {@code ORDER BY position} resolves to the select list's {@code position::text} first,
     * and sorts xid8s as strings — {@code 1000} before {@code 740}. {@code OutboxRelayIT} found it.
     */
    static final String UNPUBLISHED = """
            SELECT o.tenant_id, o.entry_id, o.position::text AS position_token, o.payload::text
              FROM outbox o
             WHERE o.published_at IS NULL
               AND o.position < pg_snapshot_xmin(pg_current_snapshot())
             ORDER BY o.position
             LIMIT ?
            """;

    static final String MARK_PUBLISHED = """
            UPDATE outbox
               SET published_at = now(), publish_seq = nextval('outbox_publish_seq')
             WHERE tenant_id = ? AND entry_id = ?
            """;

    /** One outbox row, as the drain needs it. The payload is published byte for byte and never parsed here. */
    record Row(UUID tenant, UUID entry, String position, String payload) {
    }

    /** A point between publishing a batch and marking it, where a crash is most instructive. Tests only. */
    interface CrashPoint {
        CrashPoint NONE = () -> {
        };

        void afterPublishing();
    }

    private final Producer<String, String> producer;
    private final String topic;
    private final int batchSize;

    OutboxDrain(Producer<String, String> producer, String topic, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("a batch holds at least one row, configured " + batchSize);
        }
        this.producer = producer;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    /** Publishes and marks one batch; returns how many rows it held, zero when there is nothing publishable. */
    int drainOnce(Connection connection) throws SQLException {
        return drainOnce(connection, CrashPoint.NONE);
    }

    int drainOnce(Connection connection, CrashPoint crashPoint) throws SQLException {
        connection.setAutoCommit(false);
        try {
            List<Row> batch = unpublished(connection);
            if (!batch.isEmpty()) {
                publish(batch);
                crashPoint.afterPublishing();
                markPublished(connection, batch);
            }
            connection.commit();
            return batch.size();
        } catch (SQLException | RuntimeException failure) {
            rollbackQuietly(connection, failure);
            throw failure;
        }
    }

    private List<Row> unpublished(Connection connection) throws SQLException {
        var rows = new ArrayList<Row>();
        try (PreparedStatement statement = connection.prepareStatement(UNPUBLISHED)) {
            statement.setInt(1, batchSize);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new Row(result.getObject(1, UUID.class), result.getObject(2, UUID.class),
                            result.getString(3), result.getString(4)));
                }
            }
        }
        return rows;
    }

    /**
     * Sends the batch in order and waits for every acknowledgement. The sends for one partition are
     * sequenced by the idempotent producer, so a Tenant's Entries land in the order sent. Any
     * failure fails the whole batch: nothing is marked, and the next drain sends all of it again.
     */
    private void publish(List<Row> batch) {
        var acknowledgements = new ArrayList<Future<RecordMetadata>>(batch.size());
        for (Row row : batch) {
            acknowledgements.add(producer.send(new ProducerRecord<>(topic, row.tenant().toString(), row.payload())));
        }
        producer.flush();
        for (var acknowledgement : acknowledgements) {
            try {
                acknowledgement.get();
            } catch (ExecutionException failed) {
                throw new PublishFailed(failed.getCause());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new PublishFailed(interrupted);
            }
        }
    }

    /** Marked in the order published, so {@code publish_seq} records that order. */
    private void markPublished(Connection connection, List<Row> batch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(MARK_PUBLISHED)) {
            for (Row row : batch) {
                statement.setObject(1, row.tenant());
                statement.setObject(2, row.entry());
                statement.addBatch();
            }
            int[] marked = statement.executeBatch();
            for (int count : marked) {
                if (count != 1 && count != PreparedStatement.SUCCESS_NO_INFO) {
                    throw new IllegalStateException("marking a published outbox row updated " + count + " rows");
                }
            }
        }
    }

    private static void rollbackQuietly(Connection connection, Exception cause) {
        try {
            if (!connection.isClosed()) {
                connection.rollback();
            }
        } catch (SQLException rollback) {
            cause.addSuppressed(rollback);
        }
    }

    /** Kafka did not acknowledge part of a batch. Nothing of it was marked. */
    static final class PublishFailed extends RuntimeException {
        PublishFailed(Throwable cause) {
            super("publishing an outbox batch failed; none of it is marked and all of it will be sent again", cause);
        }
    }
}
