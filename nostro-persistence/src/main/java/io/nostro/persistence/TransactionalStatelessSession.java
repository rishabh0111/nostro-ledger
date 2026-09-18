package io.nostro.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import org.hibernate.CacheMode;
import org.hibernate.Session;
import org.hibernate.StatelessSession;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A {@link StatelessSession} that is always the current transaction's.
 *
 * <p>Spring Framework 7's {@code SharedSessionCreator} promises this, but under Spring Boot's
 * {@code JpaTransactionManager} on Hibernate 7.4 it does not deliver it: in ORM 7 the
 * {@code SessionFactory} <em>is</em> the {@code EntityManagerFactory}, so the lookup finds the
 * transaction's {@code EntityManagerHolder}, which {@code currentStatelessSession} has no branch
 * for, and the attempt to bind a second holder under the same key fails with
 * "Already value ... bound to thread". The JPA-side entity-agent path that would handle it is
 * JPA 4.0 only. {@code StatelessSessionConnectionIT} is the test that found this.
 *
 * <p>So this does by hand what Spring's own {@code HibernateJpaDialect.deriveEntityAgent} does:
 * derive the stateless session from the transactional {@code Session} with {@code .connection()},
 * which Hibernate documents as sharing "the connection, and therefore also the JDBC transaction"
 * — the connection carrying {@code app.current_tenant}. Without {@code .connection()} it would be
 * a fresh pooled connection with no Tenant, and under fail-closed policies that is silent wrong
 * data rather than an error.
 *
 * <p>Outside a transaction every call throws. That is the point: a stateless session that quietly
 * borrowed its own connection is the failure mode this class exists to make impossible.
 */
public final class TransactionalStatelessSession {

    private static final Object RESOURCE_KEY = new Object();

    private TransactionalStatelessSession() {
    }

    /** The stateless session for the current transaction, opened on first use and closed at completion. */
    public static StatelessSession current(EntityManagerFactory entityManagerFactory) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "no transaction is active; a StatelessSession outside one would run on a connection without a Tenant");
        }
        if (TransactionSynchronizationManager.getResource(RESOURCE_KEY) instanceof StatelessSession bound) {
            return bound;
        }
        EntityManager entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
        if (entityManager == null) {
            throw new IllegalStateException("the active transaction has no transactional EntityManager");
        }
        StatelessSession session = entityManager.unwrap(Session.class)
                .statelessWithOptions()
                .connection()
                .open();
        // The second-level cache is off globally; this makes the session's own stance explicit too.
        session.setCacheMode(CacheMode.IGNORE);

        TransactionSynchronizationManager.bindResource(RESOURCE_KEY, session);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            // A REQUIRES_NEW transaction suspends the outer one; the outer session must go with it,
            // or the inner transaction would run on the outer connection, under the outer Tenant.
            @Override
            public void suspend() {
                TransactionSynchronizationManager.unbindResource(RESOURCE_KEY);
            }

            @Override
            public void resume() {
                TransactionSynchronizationManager.bindResource(RESOURCE_KEY, session);
            }

            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY);
                // Closing a child that shares its parent's connection releases neither the
                // connection nor the transaction; both belong to the Session that opened it.
                session.close();
            }
        });
        return session;
    }

    /** A proxy that delegates every call to {@link #current}. Injectable; never closes itself. */
    public static StatelessSession proxy(EntityManagerFactory entityManagerFactory) {
        return (StatelessSession) Proxy.newProxyInstance(
                StatelessSession.class.getClassLoader(),
                new Class<?>[] {StatelessSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "transactional StatelessSession proxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "close" -> null;
                    default -> {
                        try {
                            yield method.invoke(current(entityManagerFactory), args);
                        } catch (InvocationTargetException e) {
                            throw e.getTargetException();
                        }
                    }
                });
    }
}
