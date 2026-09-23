package io.nostro.relay;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

/**
 * The Entries topic, treated as schema: created with its partition count if it is absent, and
 * refused if it is present with any other (docs/research/ordering-and-watermarks.md section 4).
 *
 * <p>The partition count decides which partition a Tenant's key lands on. A topic with more
 * partitions than the relay expects is not a capacity improvement; it is a topic on which some
 * Tenants' future Entries land somewhere their past ones are not, with nothing ordering the two.
 * The relay will not publish into that, so it will not start.
 */
record TopicSchema(String name, int partitions, short replicationFactor) {

    TopicSchema {
        if (partitions < 1 || replicationFactor < 1) {
            throw new IllegalArgumentException("a topic needs at least one partition and one replica");
        }
    }

    /** How long a topic just created, by this process or another, may take to become describable. */
    private static final Duration PROPAGATION = Duration.ofSeconds(15);

    /** Creates the topic if absent; returns normally only if it now has exactly {@link #partitions}. */
    void ensure(Admin admin) {
        try {
            var existing = describe(admin);
            if (existing == null) {
                create(admin);
                existing = awaitDescribable(admin);
            }
            int actual = existing.partitions().size();
            if (actual != partitions) {
                throw new IllegalStateException(("topic %s has %d partitions and this relay publishes to %d: a Tenant's key "
                        + "would land on a partition that holds none of its history. Changing the count is a new topic "
                        + "and a deliberate cut-over, not a reconfiguration").formatted(name, actual, partitions));
            }
        } catch (ExecutionException failed) {
            throw new IllegalStateException("could not verify topic " + name, failed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted verifying topic " + name, interrupted);
        }
    }

    private TopicDescription describe(Admin admin) throws ExecutionException, InterruptedException {
        try {
            return admin.describeTopics(Set.of(name)).allTopicNames().get().get(name);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof UnknownTopicOrPartitionException) {
                return null;
            }
            throw failed;
        }
    }

    /**
     * A created topic is not describable until its metadata has reached the broker answering, and
     * one created a moment ago by someone else can look absent and then exist when created again.
     */
    private TopicDescription awaitDescribable(Admin admin) throws ExecutionException, InterruptedException {
        var deadline = Instant.now().plus(PROPAGATION);
        while (true) {
            var description = describe(admin);
            if (description != null) {
                return description;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("topic " + name + " was created and is still not describable after " + PROPAGATION);
            }
            Thread.sleep(100);
        }
    }

    private void create(Admin admin) throws ExecutionException, InterruptedException {
        try {
            admin.createTopics(List.of(new NewTopic(name, partitions, replicationFactor))).all().get();
        } catch (ExecutionException failed) {
            // Another process created it first; whoever did, the count is checked next.
            if (!(failed.getCause() instanceof TopicExistsException)) {
                throw failed;
            }
        }
    }
}
