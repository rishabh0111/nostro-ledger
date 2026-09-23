package io.nostro.projection;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionException;

/**
 * The projection's consumer: one thread, one Kafka consumer, one Entry at a time.
 *
 * <p><b>Offsets come from the projection's own database.</b> On assignment each partition is sought
 * to the {@code next_offset} its last apply transaction stored, or to the beginning if it has none;
 * nothing is ever committed to Kafka. A partition replayed from zero is one whose stored offset was
 * removed.
 *
 * <p><b>A message that cannot be applied halts its partition.</b> It is not skipped and not
 * dead-lettered: skipping a Posting means serving a quietly wrong Balance indefinitely, and a
 * dead-letter queue makes that feel handled (ADR-0010). The partition is paused at the message, the
 * other partitions carry on, and {@code nostro.projection.partitions.halted} says so at once. The
 * remedy is deliberate: fix what produced the message and restart, or — a decision a person makes
 * and records — move the partition's stored offset past it.
 *
 * <p>A failure that is not about the message — the database unreachable, a timeout — is retried:
 * the partition is sought back to the message and tried again after a pause.
 */
final class EntryConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EntryConsumer.class);

    private final Supplier<Consumer<String, String>> consumers;
    private final String topic;
    private final EntryApplier applier;
    private final JdbcClient jdbc;
    private final Duration retryBackoff;
    private final Set<TopicPartition> halted = ConcurrentHashMap.newKeySet();
    private final Counter halts;

    private volatile boolean running;
    private volatile Consumer<String, String> consumer;
    private Thread thread;

    EntryConsumer(Supplier<Consumer<String, String>> consumers, String topic, EntryApplier applier, JdbcClient jdbc,
                  Duration retryBackoff, MeterRegistry meters) {
        this.consumers = consumers;
        this.topic = topic;
        this.applier = applier;
        this.jdbc = jdbc;
        this.retryBackoff = retryBackoff;
        Gauge.builder("nostro.projection.partitions.halted", halted, Set::size)
                .description("Partitions stopped at a message the projection will not apply; anything but zero needs a person")
                .register(meters);
        this.halts = Counter.builder("nostro.projection.halts")
                .description("Times a partition was halted at a message the projection will not apply")
                .register(meters);
    }

    /** The partitions halted right now. */
    Set<TopicPartition> halted() {
        return Set.copyOf(halted);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        halted.clear();
        thread = Thread.ofPlatform().name("projection-consumer").start(this::run);
    }

    @Override
    public synchronized void stop() {
        running = false;
        var current = consumer;
        if (current != null) {
            current.wakeup();
        }
        if (thread != null) {
            try {
                thread.join(Duration.ofSeconds(10));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        while (running) {
            try (Consumer<String, String> kafka = consumers.get()) {
                consumer = kafka;
                kafka.subscribe(List.of(topic), new SeekToStoredOffsets(kafka));
                while (running) {
                    var records = kafka.poll(Duration.ofMillis(250));
                    for (TopicPartition partition : records.partitions()) {
                        for (ConsumerRecord<String, String> record : records.records(partition)) {
                            if (!applyOrStop(kafka, partition, record)) {
                                break;
                            }
                        }
                    }
                }
            } catch (WakeupException stopping) {
                // stop() woke the consumer
            } catch (RuntimeException failure) {
                if (running) {
                    log.warn("projection consumer failed; reconnecting", failure);
                    pause();
                }
            } finally {
                consumer = null;
            }
        }
    }

    /** Applies the record; returns false if the rest of the partition's batch must wait. */
    private boolean applyOrStop(Consumer<String, String> kafka, TopicPartition partition, ConsumerRecord<String, String> record) {
        try {
            applier.apply(record.key(), record.value(), new EntryApplier.Source(record.topic(), record.partition(), record.offset()));
            return true;
        } catch (RuntimeException failure) {
            if (isUnavailability(failure)) {
                log.warn("could not apply offset {} of {} for now; retrying it", record.offset(), partition, failure);
                kafka.seek(partition, record.offset());
                pause();
            } else {
                halt(kafka, partition, record, failure);
            }
            return false;
        }
    }

    /**
     * Whether the failure was the database being unavailable rather than anything about the
     * message: worth retrying, where anything else would fail the same way every time and halts.
     * Spring files an unreachable database under "non-transient", so the list is explicit.
     */
    static boolean isUnavailability(RuntimeException failure) {
        return failure instanceof TransientDataAccessException
                || failure instanceof RecoverableDataAccessException
                || failure instanceof DataAccessResourceFailureException
                || failure instanceof TransactionException;
    }

    private void halt(Consumer<String, String> kafka, TopicPartition partition, ConsumerRecord<String, String> record, RuntimeException why) {
        kafka.seek(partition, record.offset());
        kafka.pause(List.of(partition));
        halted.add(partition);
        halts.increment();
        log.error("HALTED {} at offset {}: the projection will not apply this message and will not skip it. "
                + "Balances of the Tenants on this partition stop advancing until a person intervenes", partition, record.offset(), why);
    }

    private void pause() {
        try {
            Thread.sleep(retryBackoff);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    /** On assignment, each partition resumes from the offset stored with the last Entry it applied. */
    private final class SeekToStoredOffsets implements ConsumerRebalanceListener {

        private final Consumer<String, String> kafka;

        SeekToStoredOffsets(Consumer<String, String> kafka) {
            this.kafka = kafka;
        }

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            for (TopicPartition partition : partitions) {
                storedOffset(partition).ifPresentOrElse(
                        next -> kafka.seek(partition, next),
                        () -> kafka.seekToBeginning(List.of(partition)));
            }
        }

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            halted.removeAll(partitions);
        }

        private Optional<Long> storedOffset(TopicPartition partition) {
            return jdbc.sql("SELECT next_offset FROM consumer_position WHERE topic = ? AND partition = ?")
                    .params(partition.topic(), partition.partition())
                    .query(Long.class)
                    .optional();
        }
    }
}
