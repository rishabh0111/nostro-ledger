package io.nostro.persistence.ledger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.nostro.domain.EntryRecorder;
import io.nostro.domain.LedgerCommand;
import io.nostro.domain.RecordOutcome;
import io.nostro.domain.RecordOutcome.AlreadyReversed;
import io.nostro.domain.RecordOutcome.InsufficientBalance;
import io.nostro.domain.RecordOutcome.Recorded;
import io.nostro.domain.ReverseEntry;
import java.time.Duration;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;

/**
 * The retry boundary around {@link EntryWriter}: a different bean, outside the transaction proxy,
 * because a serialization failure can surface at COMMIT after the transactional method has
 * returned, and because once any statement errors Postgres has aborted the transaction and the
 * only recovery is a fresh one (docs/research/hot-account-contention.md section 3).
 *
 * <p>Three kinds of database refusal come out of the writer as exceptions and are answered here:
 * <ul>
 *   <li>{@code 40001}, {@code 40P01}, {@code 55P03} — transient; retried with jittered backoff.</li>
 *   <li>{@code 23505} on the Idempotency Key — a duplicate that was in flight when this attempt
 *       started, blocked on the unique index until the first committed, then failed. Run once more:
 *       the record now exists and the writer replays it (ADR-0005).</li>
 *   <li>{@code 23505} on {@code entry_reversed_at_most_once} — two reversals raced; the loser is
 *       told so.</li>
 * </ul>
 * {@code 23514} is never retried: it is the answer "insufficient balance", already returned as a
 * value by the guarded UPDATE before the CHECK is ever reached.
 *
 * <p>Three metrics, because this is where the write path's costs are visible:
 * {@code nostro.ledger.entries.write}, the whole
 * call's latency, retries included, split by whether it took a Constrained Account's row lock —
 * ADR-0004's asymmetry, measured; {@code nostro.ledger.floor.rejections}, the floor refusing; and
 * {@code nostro.ledger.sql.failures} by SQLSTATE, every database refusal the writer met, retried or
 * not, which is the histogram the load test reads.
 */
@Component
public class JpaEntryRecorder implements EntryRecorder {

    static final String IDEMPOTENCY_KEY_INDEX = "idempotency_record_pkey";
    static final String REVERSED_AT_MOST_ONCE = "entry_reversed_at_most_once";

    private final EntryWriter writer;
    private final RetryTemplate transientFailures;
    private final MeterRegistry meters;
    private final Counter floorRejections;

    JpaEntryRecorder(EntryWriter writer, MeterRegistry meters) {
        this.writer = writer;
        this.meters = meters;
        this.floorRejections = Counter.builder("nostro.ledger.floor.rejections")
                .description("Entries refused because a Constrained Account would have gone below zero")
                .register(meters);
        this.transientFailures = new RetryTemplate(RetryPolicy.builder()
                .predicate(SqlFailure::isTransient)
                .maxRetries(3)
                .delay(Duration.ofMillis(25))
                .multiplier(2.0)
                .maxDelay(Duration.ofMillis(400))
                .jitter(Duration.ofMillis(25))
                .build());
    }

    @Override
    public RecordOutcome record(LedgerCommand command) {
        var footprint = new EntryWriter.Footprint();
        var started = Timer.start(meters);
        RecordOutcome outcome = null;
        try {
            outcome = recordWithRetries(command, footprint);
            if (outcome instanceof InsufficientBalance) {
                floorRejections.increment();
            }
            return outcome;
        } finally {
            started.stop(Timer.builder("nostro.ledger.entries.write")
                    .description("Recording an Entry, retries included, by whether it took a Constrained Account's row lock")
                    .tag("constrained", String.valueOf(footprint.constrained()))
                    .tag("outcome", outcome instanceof Recorded ? "recorded" : outcome == null ? "failed" : "refused")
                    .publishPercentileHistogram()
                    .register(meters));
        }
    }

    private RecordOutcome recordWithRetries(LedgerCommand command, EntryWriter.Footprint footprint) {
        try {
            return transientFailures.execute(() -> writeOnce(command, footprint));
        } catch (RetryException retry) {
            Throwable last = retry.getLastException();
            if (SqlFailure.isTransient(last)) {
                throw new IllegalStateException(
                        "recording an Entry kept failing transiently after " + retry.getRetryCount() + " retries", last);
            }
            // Not retryable: the template only wrapped it. Rethrow what the writer threw.
            throw last instanceof RuntimeException runtime ? runtime : new IllegalStateException(last);
        }
    }

    private RecordOutcome writeOnce(LedgerCommand command, EntryWriter.Footprint footprint) {
        try {
            return writer.write(command, footprint);
        } catch (RuntimeException failure) {
            SqlFailure.of(failure).ifPresent(sql -> Counter.builder("nostro.ledger.sql.failures")
                    .description("Database refusals met while recording an Entry, by SQLSTATE, whether or not they were retried")
                    .tag("sqlstate", sql.sqlState())
                    .register(meters)
                    .increment());
            if (SqlFailure.violates(failure, IDEMPOTENCY_KEY_INDEX)) {
                return writer.write(command, footprint);
            }
            if (SqlFailure.violates(failure, REVERSED_AT_MOST_ONCE) && command instanceof ReverseEntry reverse) {
                return new AlreadyReversed(reverse.reverses());
            }
            throw failure;
        }
    }
}
