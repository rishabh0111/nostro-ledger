package io.nostro.projection;

import io.nostro.domain.Position;
import io.nostro.domain.TenantId;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * Where a waiting reader parks until the Tenant's watermark reaches the Position it needs
 * (ADR-0007). The consumer signals here after each commit; a reader waits here holding no JDBC
 * connection, so a lagging projection costs parked threads, not the connection pool.
 *
 * <p>This is a wake-up signal, not a source of truth. It knows only what this process applied
 * since it started; the answer a reader gets is always read from the database afterwards. A
 * reader that is never signalled — because another instance consumes its Tenant's partition, or
 * nothing arrives — wakes at its deadline and reads what is there, which is still the truth.
 *
 * <p>A lock and a condition rather than {@code synchronized}: on Java 21 a virtual thread that
 * waits inside a monitor pins its carrier thread.
 */
@Component
public class Watermarks {

    private final ConcurrentHashMap<TenantId, Signal> signals = new ConcurrentHashMap<>();

    /** Called by the consumer once an Entry's transaction has committed. */
    void advanced(TenantId tenant, Position position) {
        signals.computeIfAbsent(tenant, t -> new Signal()).advance(position);
    }

    /**
     * Parks until this process has applied an Entry of the Tenant at or beyond {@code minimum}, or
     * the wait runs out. Returns whether it saw the watermark get there; either way, the caller
     * reads the answer from the database next.
     */
    boolean awaitAtLeast(TenantId tenant, Position minimum, Duration wait) throws InterruptedException {
        return signals.computeIfAbsent(tenant, t -> new Signal()).awaitAtLeast(minimum, wait);
    }

    private static final class Signal {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition advanced = lock.newCondition();
        private Position highest;

        void advance(Position position) {
            lock.lock();
            try {
                if (highest == null || highest.installation() != position.installation() || position.isAtLeast(highest)) {
                    highest = position;
                }
                advanced.signalAll();
            } finally {
                lock.unlock();
            }
        }

        boolean awaitAtLeast(Position minimum, Duration wait) throws InterruptedException {
            long remaining = wait.toNanos();
            lock.lock();
            try {
                while (!reached(minimum)) {
                    if (remaining <= 0) {
                        return false;
                    }
                    remaining = advanced.awaitNanos(remaining);
                }
                return true;
            } finally {
                lock.unlock();
            }
        }

        private boolean reached(Position minimum) {
            return highest != null && highest.installation() == minimum.installation() && highest.isAtLeast(minimum);
        }
    }
}
