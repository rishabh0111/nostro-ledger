package io.nostro.persistence;

import io.nostro.persistence.entity.TenantScopedId;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.hibernate.StatelessSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = TenantScopedId.class)
class PersistenceConfiguration {

    /**
     * A transactional {@link StatelessSession} proxy. Boot does not auto-configure one, and
     * Framework's {@code SharedSessionCreator} does not work under {@code JpaTransactionManager}
     * on this stack; see {@link TransactionalStatelessSession} for why, and
     * {@code StatelessSessionConnectionIT} for the proof that this one runs on the transaction's
     * own connection.
     */
    @Bean
    StatelessSession statelessSession(EntityManagerFactory entityManagerFactory) {
        return TransactionalStatelessSession.proxy(entityManagerFactory);
    }

    /** The installation identity that prefixes every Position, read once at startup. */
    @Bean
    Installation installation(DataSource dataSource) {
        long systemIdentifier = JdbcClient.create(dataSource)
                .sql("SELECT system_identifier FROM installation")
                .query(Long.class)
                .single();
        return new Installation(systemIdentifier);
    }
}
