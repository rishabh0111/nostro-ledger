package io.nostro.relay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The outbox relay: its own deployable, run as exactly one instance, because the ordering it
 * provides needs a single writer while the API service beside it needs many replicas (ADR-0009).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OutboxRelayApplication {

    public static void main(String[] args) {
        SpringApplication.run(OutboxRelayApplication.class, args);
    }
}
