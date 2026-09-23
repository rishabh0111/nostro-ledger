package io.nostro.testsupport;

import org.testcontainers.containers.GenericContainer;

/**
 * One Redis for the whole suite, the image compose runs. Redis holds per-Tenant rate limits and
 * nothing else (ADR-0008), so this is the only thing the suite needs it for.
 */
public final class LedgerRedis {

    public static final String IMAGE = "redis:7.4-alpine";

    private static final GenericContainer<?> CONTAINER = new GenericContainer<>(IMAGE).withExposedPorts(6379);

    private LedgerRedis() {
    }

    public static GenericContainer<?> instance() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return CONTAINER;
    }

    /** A {@code redis://} URI for the running container. */
    public static String uri() {
        var redis = instance();
        return "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
    }
}
