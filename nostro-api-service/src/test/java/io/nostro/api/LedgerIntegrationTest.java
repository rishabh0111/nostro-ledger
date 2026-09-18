package io.nostro.api;

import io.nostro.domain.TenantId;
import io.nostro.persistence.tenant.TenantContext;
import io.nostro.testsupport.LedgerPostgres;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The one Spring context every database test shares (ADR-0012): the real application, against the
 * suite's singleton Postgres, connected as the restricted request-path role. Subclasses add no
 * properties, no mocked beans and no {@code @DirtiesContext}, because each of those is a second
 * context and a second application startup.
 */
@SpringBootTest(properties = {
        "NOSTRO_APP_PASSWORD=app-secret",
        "NOSTRO_CONTROL_PASSWORD=control-secret",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
public abstract class LedgerIntegrationTest {

    static final PostgreSQLContainer POSTGRES = LedgerPostgres.instance();

    @DynamicPropertySource
    static void ledgerDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    /** The owner: the container's superuser, which bypasses every policy. For fixtures only. */
    protected JdbcClient asOwner() {
        var owner = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        return JdbcClient.create(owner);
    }

    /** Creates a Tenant directly, as the owner. The control plane arrives later. */
    protected TenantId newTenant() {
        var id = TenantId.random();
        asOwner().sql("INSERT INTO tenant (id, name) VALUES (?, ?)")
                .param(id.value()).param("tenant-" + id.value())
                .update();
        return id;
    }

    /** Runs work in one transaction as the request-path role, acting for the Tenant given. */
    protected <T> T inTransactionAs(TenantId tenant, Supplier<T> work) {
        return TenantContext.runAs(tenant, () -> new TransactionTemplate(transactionManager).execute(status -> work.get()));
    }

    protected void inTransactionAs(TenantId tenant, Runnable work) {
        inTransactionAs(tenant, () -> {
            work.run();
            return null;
        });
    }

    /** Runs work in one transaction as the request-path role with no Tenant bound. */
    protected <T> T inTransactionWithoutTenant(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    /** A JdbcClient on the request-path DataSource; inside a transaction it uses that transaction's connection. */
    protected JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    protected static UUID uuid() {
        return UUID.randomUUID();
    }

    /** The five-character SQLSTATE at the root of a failure, or null if there is no SQLException. */
    protected static String sqlState(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
