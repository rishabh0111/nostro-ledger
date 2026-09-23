package io.nostro.testsupport;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The projection's own Postgres: a second container, never a second database on the ledger's, so
 * that nothing the projection's tests do can reach the ledger's tables even by accident (ADR-0008).
 * A suite singleton, like {@link LedgerPostgres}, and the same image.
 *
 * <p>The container's superuser is the owner, which runs the projection's migrations; the service
 * itself connects as the restricted role those migrations create.
 */
public final class ProjectionPostgres {

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(LedgerPostgres.IMAGE)
            .withDatabaseName("nostro_projection");

    private ProjectionPostgres() {
    }

    public static PostgreSQLContainer instance() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return CONTAINER;
    }
}
