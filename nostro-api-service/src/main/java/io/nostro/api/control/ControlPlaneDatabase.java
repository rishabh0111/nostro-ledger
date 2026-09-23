package io.nostro.api.control;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The control plane's own connection pool, connected as {@code nostro_control} (ADR-0015). It is
 * deliberately not a {@code DataSource} bean: the application has one of those, the request path's,
 * and everything transactional and tenant-scoped binds to it. What the control plane touches --
 * {@code tenant}, {@code api_key}, {@code staff_user} -- is not under row-level security and needs
 * no Tenant, so it runs here as plain JDBC, one statement at a time, outside any {@code
 * @Transactional} boundary.
 *
 * <p>Small: two connections is plenty for a surface that is used to set up callers, not to serve
 * them. Built at first use rather than at startup: Hikari connects when it is built, and the role
 * it connects as is created by the first migration — which runs in this process under test, and in
 * a migration container beforehand under compose. Either way it has run by the time anything asks.
 */
@Component
@EnableConfigurationProperties(ControlProperties.class)
class ControlPlaneDatabase implements AutoCloseable {

    private final ControlProperties properties;
    private HikariDataSource pool;

    ControlPlaneDatabase(ControlProperties properties) {
        this.properties = properties;
    }

    /** A client over the control-plane pool. Outside a transaction, each statement borrows and returns a connection. */
    JdbcClient jdbc() {
        return JdbcClient.create(pool());
    }

    private synchronized HikariDataSource pool() {
        if (pool == null) {
            var config = new HikariConfig();
            config.setPoolName("nostro-control");
            config.setJdbcUrl(properties.datasource().url());
            config.setUsername(properties.datasource().username());
            config.setPassword(properties.datasource().password());
            config.setMaximumPoolSize(2);
            // Visible in pg_stat_activity, so that which role the control plane connects as is observable from outside.
            config.addDataSourceProperty("ApplicationName", "nostro-control");
            pool = new HikariDataSource(config);
        }
        return pool;
    }

    @Override
    public synchronized void close() {
        if (pool != null) {
            pool.close();
        }
    }
}
