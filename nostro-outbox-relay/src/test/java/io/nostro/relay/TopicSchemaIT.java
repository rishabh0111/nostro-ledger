package io.nostro.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The partition count is schema: created once, and a topic that disagrees stops the relay from starting. */
class TopicSchemaIT {

    private static Admin admin;

    @BeforeAll
    static void admin() {
        admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, RelayFixture.KAFKA.getBootstrapServers()));
    }

    @AfterAll
    static void close() {
        admin.close();
    }

    @Test
    @DisplayName("an absent topic is created with the fixed partition count, and ensuring it again changes nothing")
    void anAbsentTopicIsCreated() throws Exception {
        var schema = new TopicSchema("schema-" + UUID.randomUUID(), 12, (short) 1);

        schema.ensure(admin);
        schema.ensure(admin);

        assertThat(partitions(schema.name())).isEqualTo(12);
    }

    @Test
    @DisplayName("a topic with any other partition count is refused: a Tenant's key would land where none of its history is")
    void anotherPartitionCountIsRefused() throws Exception {
        var name = "schema-" + UUID.randomUUID();
        admin.createTopics(List.of(new NewTopic(name, 13, (short) 1))).all().get();

        assertThatThrownBy(() -> new TopicSchema(name, 12, (short) 1).ensure(admin))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has 13 partitions")
                .hasMessageContaining("publishes to 12");
    }

    private static int partitions(String name) throws Exception {
        return admin.describeTopics(Set.of(name)).allTopicNames().get().get(name).partitions().size();
    }
}
