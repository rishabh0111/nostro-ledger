package io.nostro.persistence.ledger;

import io.nostro.domain.EntryRecorder;
import io.nostro.domain.LedgerCommand;
import io.nostro.domain.RecordOutcome;
import io.nostro.domain.RecordOutcome.AlreadyReversed;
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
 */
@Component
public class JpaEntryRecorder implements EntryRecorder {

    static final String IDEMPOTENCY_KEY_INDEX = "idempotency_record_pkey";
    static final String REVERSED_AT_MOST_ONCE = "entry_reversed_at_most_once";

    private final EntryWriter writer;
    private final RetryTemplate transientFailures;

    JpaEntryRecorder(EntryWriter writer) {
        this.writer = writer;
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
        try {
            return transientFailures.execute(() -> writeOnce(command));
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

    private RecordOutcome writeOnce(LedgerCommand command) {
        try {
            return writer.write(command);
        } catch (RuntimeException failure) {
            if (SqlFailure.violates(failure, IDEMPOTENCY_KEY_INDEX)) {
                return writer.write(command);
            }
            if (SqlFailure.violates(failure, REVERSED_AT_MOST_ONCE) && command instanceof ReverseEntry reverse) {
                return new AlreadyReversed(reverse.reverses());
            }
            throw failure;
        }
    }
}
