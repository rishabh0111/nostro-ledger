package io.nostro.testsupport;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One Postgres for the whole suite, started on first use and stopped by Ryuk when the JVM exits.
 * A singleton rather than {@code withReuse(true)}, which Testcontainers documents as unsuited to CI
 * (docs/research/ci-and-testcontainers-budget.md section 4).
 *
 * <p>The container's superuser is the <em>owner</em> role: it runs the migrations. Request-path
 * tests must connect as the restricted role the migrations create, never as this user, because a
 * superuser bypasses every row-level security policy.
 */
public final class LedgerPostgres {

    /** Pinned to the image tag docker compose uses, so CI and the local stack agree. */
    public static final String IMAGE = "postgres:16-alpine";

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(IMAGE);

    private LedgerPostgres() {
    }

    public static PostgreSQLContainer instance() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return CONTAINER;
    }
}
