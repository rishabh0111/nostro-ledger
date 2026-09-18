package io.nostro.persistence;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.hibernate.SharedSessionCreator;

@Configuration(proxyBeanMethods = false)
class PersistenceConfiguration {

    /**
     * A transactional {@link StatelessSession} proxy. Boot does not auto-configure one. Each call
     * inside a transaction is delegated to a stateless session opened on that transaction's own
     * JDBC connection — the one carrying {@code app.current_tenant} — which is a fact
     * established by test rather than by reading (docs/research/spring-boot-4-for-this-design.md
     * section 3).
     */
    @Bean
    StatelessSession statelessSession(EntityManagerFactory entityManagerFactory) {
        return SharedSessionCreator.createSharedStatelessSession(entityManagerFactory.unwrap(SessionFactory.class));
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
