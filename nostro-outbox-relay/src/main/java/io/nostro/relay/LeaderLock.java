package io.nostro.relay;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Which relay is the single writer: the one whose connection holds a session-level advisory lock
 * (docs/research/ordering-and-watermarks.md section 3).
 *
 * <p>Session-level, so it is released when the session ends — including when the relay's process
 * dies or its connection is cut, which is what makes failover automatic. It is held by the same
 * connection that drains, never a pooled one: under a transaction-mode pooler a session lock and a
 * borrowed connection are incompatible, and a lock held anywhere but on the draining connection is
 * a lock that can outlive, or be outlived by, the work it guards.
 */
final class LeaderLock {

    /** An arbitrary constant, fixed forever: every relay against one ledger must contend on the same key. */
    static final long KEY = 0x6e6f7374726f0001L;

    private LeaderLock() {
    }

    /** Takes the lock if it is free and returns whether this connection now holds it. Never waits. */
    static boolean tryAcquire(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, KEY);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }
}
