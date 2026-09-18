package io.nostro.persistence.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/** The retry and refusal decisions switch on what the server said, so what the server said must be read correctly. */
class SqlFailureTest {

    @Test
    void readsSqlStateAndConstraintOffAPsqlException() {
        var failure = wrapped(serverError("23505", "idempotency_record_pkey"));

        assertThat(SqlFailure.of(failure)).contains(new SqlFailure("23505", Optional.of("idempotency_record_pkey")));
        assertThat(SqlFailure.of(failure).orElseThrow().violates("idempotency_record_pkey")).isTrue();
        assertThat(SqlFailure.of(failure).orElseThrow().violates("entry_reversed_at_most_once")).isFalse();
    }

    @Test
    void followsABatchUpdateExceptionToTheServerErrorBehindIt() {
        // A batched insert fails as a BatchUpdateException whose PSQLException is the *next* exception.
        var batch = new BatchUpdateException("Batch entry 0 ... was aborted", "23505", 0, new int[0], null);
        batch.setNextException(serverError("23505", "entry_reversed_at_most_once"));

        assertThat(SqlFailure.of(wrapped(batch)))
                .contains(new SqlFailure("23505", Optional.of("entry_reversed_at_most_once")));
    }

    @Test
    void onlySerializationDeadlockAndLockTimeoutAreTransient() {
        assertThat(SqlFailure.isTransient(wrapped(serverError("40001", null)))).isTrue();
        assertThat(SqlFailure.isTransient(wrapped(serverError("40P01", null)))).isTrue();
        assertThat(SqlFailure.isTransient(wrapped(serverError("55P03", null)))).isTrue();

        assertThat(SqlFailure.isTransient(wrapped(serverError("23514", "account_constrained_non_negative"))))
                .as("insufficient balance is an answer, never a retry").isFalse();
        assertThat(SqlFailure.isTransient(wrapped(serverError("57014", null)))).as("statement timeout").isFalse();
        assertThat(SqlFailure.isTransient(wrapped(serverError("42501", null)))).isFalse();
        assertThat(SqlFailure.isTransient(new IllegalStateException("not from the database"))).isFalse();
    }

    @Test
    void aFailureWithNoSqlExceptionIsNotASqlFailure() {
        assertThat(SqlFailure.of(new IllegalStateException("plain"))).isEmpty();
        assertThat(SqlFailure.of(new RuntimeException(new SQLException("no state", (String) null)))).isEmpty();
    }

    private static PSQLException serverError(String sqlState, String constraint) {
        var fields = new StringBuilder("SERROR\0C").append(sqlState).append('\0');
        if (constraint != null) {
            fields.append('n').append(constraint).append('\0');
        }
        fields.append("Mmessage\0");
        return new PSQLException(new ServerErrorMessage(fields.toString()));
    }

    /** As it arrives from the writer: Spring and Hibernate wrappers around the driver's exception. */
    private static RuntimeException wrapped(SQLException cause) {
        return new RuntimeException("translated", new RuntimeException("hibernate", cause));
    }
}
