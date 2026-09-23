package io.nostro.projection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The projection service: consumes Entries from Kafka into Balances in a database of its own, and
 * answers for them (ADR-0008, ADR-0010).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ProjectionApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProjectionApplication.class, args);
    }
}
