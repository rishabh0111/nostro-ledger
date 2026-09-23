package io.nostro.projection;

import io.nostro.domain.TenantId;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one way this service touches its Tenant-scoped tables: a transaction that, before anything
 * else, sets {@code app.current_tenant} transaction-locally on its own connection — the ledger's
 * technique, applied again (docs/research/rls-pooling.md section 2).
 *
 * <p>The {@link JdbcClient} handed to the work runs on the transaction's connection, because
 * {@code DataSourceTransactionManager} binds it to the thread and the client asks for it through
 * {@code DataSourceUtils}. Work done without going through here has no Tenant, and the policies
 * answer it with nothing.
 */
@Component
public class TenantTransactions {

    static final String SET_TENANT = "SELECT set_config('app.current_tenant', ?, true)";

    private final JdbcClient jdbc;
    private final TransactionTemplate readWrite;
    private final TransactionTemplate readOnly;

    TenantTransactions(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.readWrite = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /** Runs the work in one read-write transaction acting for the Tenant. */
    public <T> T writingAs(TenantId tenant, Function<JdbcClient, T> work) {
        return readWrite.execute(status -> {
            bind(tenant);
            return work.apply(jdbc);
        });
    }

    /** Runs the work in one read-only transaction acting for the Tenant. */
    public <T> T readingAs(TenantId tenant, Function<JdbcClient, T> work) {
        return readOnly.execute(status -> {
            bind(tenant);
            return work.apply(jdbc);
        });
    }

    private void bind(TenantId tenant) {
        jdbc.sql(SET_TENANT).param(tenant.toString()).query(String.class).single();
    }
}
