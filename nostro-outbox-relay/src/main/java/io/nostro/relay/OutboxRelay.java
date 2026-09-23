package io.nostro.relay;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The relay's one thread: take the lead, then drain until stopped; if the lead cannot be taken,
 * stand by and try again; if anything fails, drop the connection — and with it the lead — and
 * start over.
 *
 * <p>Dropping the connection on every failure is deliberate. The lock and the drain share it, so
 * a relay that has lost its connection has lost the lead in the same instant, and a relay that
 * still holds the lead is still the one draining. A failed batch is sent again by whichever relay
 * leads next, which may be this one; nothing of it was marked (see {@link OutboxDrain}).
 *
 * <p>One thread, not a scheduler's pool: the ordering holds only while exactly one drain runs.
 */
final class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    enum State { STOPPED, STANDBY, LEADING }

    /** Opens a new, dedicated, unpooled connection to the ledger as the relay's role. */
    @FunctionalInterface
    interface Database {
        Connection connect() throws SQLException;
    }

    /**
     * @param idlePoll       how long to wait after a drain found nothing publishable
     * @param standbyRetry   how often a relay that does not lead tries to
     * @param failureBackoff how long to wait after a failure before reconnecting
     */
    record Timing(Duration idlePoll, Duration standbyRetry, Duration failureBackoff) {
    }

    private final Database database;
    private final OutboxDrain drain;
    private final Timing timing;

    private volatile boolean running;
    private volatile State state = State.STOPPED;
    private Thread thread;

    OutboxRelay(Database database, OutboxDrain drain, Timing timing) {
        this.database = database;
        this.drain = drain;
        this.timing = timing;
    }

    State state() {
        return state;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = Thread.ofPlatform().name("outbox-relay").start(this::run);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(Duration.ofSeconds(10));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        thread = null;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        while (running) {
            try (Connection connection = database.connect()) {
                if (!LeaderLock.tryAcquire(connection)) {
                    if (state != State.STANDBY) {
                        log.info("another relay holds the lead; standing by");
                    }
                    state = State.STANDBY;
                    pause(timing.standbyRetry());
                    continue;
                }
                state = State.LEADING;
                log.info("leading: this relay is the outbox's single writer");
                while (running) {
                    if (drain.drainOnce(connection) == 0) {
                        pause(timing.idlePoll());
                    }
                }
            } catch (SQLException | RuntimeException failure) {
                if (running) {
                    log.warn("relay failed; dropping its connection and the lead with it, then retrying", failure);
                    state = State.STANDBY;
                    pause(timing.failureBackoff());
                }
            }
        }
        state = State.STOPPED;
    }

    private void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            running = false;
            Thread.currentThread().interrupt();
        }
    }
}
