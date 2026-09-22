package io.nostro.persistence.ledger;

import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import org.postgresql.util.PSQLException;

/**
 * What Postgres said, read off the cause chain: the SQLSTATE and, for a constraint violation,
 * the constraint's name. The retry and refusal decisions switch on these strings, never on Spring's
 * exception types, because pgjdbc throws plain {@code PSQLException} and Spring's translator has no
 * entry for class 55 (docs/research/hot-account-contention.md section 3).
 */
record SqlFailure(String sqlState, Optional<String> constraint) {

    static final String SERIALIZATION_FAILURE = "40001";
    static final String DEADLOCK_DETECTED = "40P01";
    static final String LOCK_NOT_AVAILABLE = "55P03";
    static final String UNIQUE_VIOLATION = "23505";

    /** Retryable from outside the transaction: nothing was wrong with the work, only with the timing. */
    private static final Set<String> TRANSIENT = Set.of(SERIALIZATION_FAILURE, DEADLOCK_DETECTED, LOCK_NOT_AVAILABLE);

    static Optional<SqlFailure> of(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                // A BatchUpdateException carries the server's PSQLException as its *next* exception,
                // not its cause, and only that one knows the constraint's name.
                for (SQLException s = sql; s != null; s = s.getNextException()) {
                    if (s instanceof PSQLException pg && pg.getServerErrorMessage() != null) {
                        return Optional.of(new SqlFailure(
                                pg.getSQLState(), Optional.ofNullable(pg.getServerErrorMessage().getConstraint())));
                    }
                }
                return Optional.of(new SqlFailure(sql.getSQLState(), Optional.empty()));
            }
        }
        return Optional.empty();
    }

    /** Whether the failure is a unique violation of the constraint named, wherever in the cause chain Postgres said so. */
    static boolean violates(Throwable failure, String constraintName) {
        return of(failure).map(sql -> sql.violates(constraintName)).orElse(false);
    }

    static boolean isTransient(Throwable failure) {
        return of(failure).map(SqlFailure::isTransient).orElse(false);
    }

    boolean isTransient() {
        return TRANSIENT.contains(sqlState);
    }

    boolean violates(String constraintName) {
        return UNIQUE_VIOLATION.equals(sqlState) && constraint.filter(constraintName::equals).isPresent();
    }
}
