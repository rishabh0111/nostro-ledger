package io.nostro.api.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.nostro.api.LedgerIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The spike ADR-0013 asks for, kept in the suite. Tenant isolation rests on every request-path
 * query running on the connection that had {@code set_config('app.current_tenant', ...)} applied.
 * Spring's source says the transactional StatelessSession shares the transaction's connection under
 * JpaTransactionManager; no document states it end to end. Under fail-closed policies the failure
 * mode is an empty result set, not an error, so it is asserted here rather than observed.
 */
class StatelessSessionConnectionIT extends LedgerIntegrationTest {

    @Autowired
    EntityManager entityManager;

    @Autowired
    StatelessSession statelessSession;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("inside one transaction, the EntityManager, the StatelessSession and JDBC share one backend pid")
    void everyPathRunsOnTheTransactionsConnection() {
        var tenant = newTenant();

        var pids = inTransactionAs(tenant, () -> new long[] {
                ((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).longValue(),
                ((Number) statelessSession.createNativeQuery("SELECT pg_backend_pid()", Integer.class).getSingleResult()).longValue(),
                jdbc().sql("SELECT pg_backend_pid()").query(Long.class).single()
        });

        assertThat(pids[1]).as("StatelessSession pid equals EntityManager pid").isEqualTo(pids[0]);
        assertThat(pids[2]).as("JDBC pid equals EntityManager pid").isEqualTo(pids[0]);
    }

    @Test
    @DisplayName("the StatelessSession sees the Tenant the hook set for this transaction")
    void theStatelessSessionSeesTheTenantContext() {
        var tenant = newTenant();

        String seen = inTransactionAs(tenant, () -> statelessSession
                .createNativeQuery("SELECT nullif(current_setting('app.current_tenant', true), '')", String.class)
                .getSingleResult());

        assertThat(seen).isEqualTo(tenant.value().toString());
    }

    @Test
    @DisplayName("two transactions on the pool never see each other's Tenant")
    void theTenantDoesNotLeakAcrossTransactions() {
        var a = newTenant();
        var b = newTenant();

        String seenByA = inTransactionAs(a, this::currentTenantThroughStatelessSession);
        String seenByB = inTransactionAs(b, this::currentTenantThroughStatelessSession);
        String seenWithout = inTransactionWithoutTenant(this::currentTenantThroughStatelessSession);

        assertThat(seenByA).isEqualTo(a.value().toString());
        assertThat(seenByB).isEqualTo(b.value().toString());
        assertThat(seenWithout).isNull();
    }

    @Test
    @DisplayName("outside a transaction the StatelessSession refuses to work rather than borrowing a connection")
    void outsideATransactionTheStatelessSessionFailsLoudly() {
        var failure = catchThrowable(() -> statelessSession
                .createNativeQuery("SELECT pg_backend_pid()", Integer.class).getSingleResult());

        assertThat(failure).as("a query outside any transaction must not silently run on a Tenant-less connection")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no transaction is active");
    }

    @Test
    @DisplayName("a write through the StatelessSession commits with the transaction, and the session closes with it")
    void theStatelessSessionCommitsWithItsTransaction() {
        var tenant = newTenant();
        var account = uuid();

        inTransactionAs(tenant, () -> statelessSession
                .createNativeMutationQuery(
                        "INSERT INTO account (tenant_id, id, code, currency, constrained) VALUES (?1, ?2, 'cash', 'USD', false)")
                .setParameter(1, tenant.value()).setParameter(2, account)
                .executeUpdate());
        var seenAfterCommit = inTransactionAs(tenant, () -> jdbc()
                .sql("SELECT count(*) FROM account WHERE id = ?").param(account).query(Long.class).single());

        assertThat(seenAfterCommit).isEqualTo(1);
    }

    @Test
    @DisplayName("within one transaction every call reaches the same StatelessSession; the next transaction gets its own")
    void oneStatelessSessionPerTransaction() {
        var tenant = newTenant();

        var first = inTransactionAs(tenant, () -> new Object[] {
                statelessSession.unwrap(StatelessSession.class), statelessSession.unwrap(StatelessSession.class)});
        var second = inTransactionAs(tenant, () -> statelessSession.unwrap(StatelessSession.class));

        assertThat(first[0]).isSameAs(first[1]);
        assertThat(second).isNotSameAs(first[0]);
        assertThat(((StatelessSession) first[0]).isOpen()).as("closed at completion").isFalse();
    }

    @Test
    @DisplayName("the second-level cache is off, so nothing outlives the transaction that authorised loading it")
    void theSecondLevelCacheIsOff() {
        var options = entityManagerFactory.unwrap(SessionFactory.class).getSessionFactoryOptions();

        assertThat(options.isSecondLevelCacheEnabled()).isFalse();
        assertThat(options.isQueryCacheEnabled()).isFalse();
    }

    private String currentTenantThroughStatelessSession() {
        return statelessSession
                .createNativeQuery("SELECT nullif(current_setting('app.current_tenant', true), '')", String.class)
                .getSingleResult();
    }
}
