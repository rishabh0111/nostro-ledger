package io.nostro.persistence.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.PreparedStatement;
import org.hibernate.Session;
import org.jspecify.annotations.Nullable;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * The one centralised hook that gives the database its Tenant.
 *
 * <p>After every transaction begins and before any application query runs, issues
 * {@code set_config('app.current_tenant', ?, true)} — a bound parameter, transaction-local — on the
 * transaction's own JDBC connection, obtained through the transactional Hibernate session so that
 * it cannot be a second connection borrowed from the pool. Spring Boot attaches every
 * {@link TransactionExecutionListener} bean to its transaction manager, which is what makes this
 * something no route can forget (docs/research/rls-pooling.md section 2).
 *
 * <p>With no Tenant bound, nothing is set and the policies match no rows.
 */
@Component
class TenantContextTransactionListener implements TransactionExecutionListener {

    static final String SET_TENANT = "SELECT set_config('app.current_tenant', ?, true)";

    private final EntityManagerFactory entityManagerFactory;

    TenantContextTransactionListener(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @Override
    public void afterBegin(TransactionExecution transaction, @Nullable Throwable beginFailure) {
        if (beginFailure != null) {
            return;
        }
        TenantContext.current().ifPresent(tenant -> {
            EntityManager entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            if (entityManager == null) {
                throw new IllegalStateException(
                        "transaction " + transaction.getTransactionName() + " began without a transactional EntityManager");
            }
            entityManager.unwrap(Session.class).doWork(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(SET_TENANT)) {
                    statement.setString(1, tenant.value().toString());
                    statement.execute();
                }
            });
        });
    }
}
