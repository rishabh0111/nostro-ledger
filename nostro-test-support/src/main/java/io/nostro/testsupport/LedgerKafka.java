package io.nostro.testsupport;

import org.testcontainers.kafka.KafkaContainer;

/**
 * One Kafka broker for the whole suite, in KRaft mode, started on first use and stopped by Ryuk
 * when the JVM exits: a real broker rather than an embedded one, because what the suite has to
 * prove about Kafka — partitioning by key, idempotent production, offsets — is the broker's
 * behaviour, not a fake's (docs/research/ci-and-testcontainers-budget.md section 6).
 */
public final class LedgerKafka {

    /** The image compose runs, so CI and the local stack agree; its minor matches the client Boot manages. */
    public static final String IMAGE = "apache/kafka:4.2.1";

    private static final KafkaContainer CONTAINER = new KafkaContainer(IMAGE);

    private LedgerKafka() {
    }

    public static KafkaContainer instance() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return CONTAINER;
    }
}
