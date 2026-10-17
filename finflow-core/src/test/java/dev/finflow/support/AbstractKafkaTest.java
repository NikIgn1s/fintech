package dev.finflow.support;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class AbstractKafkaTest extends AbstractPostgresTest {

    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    private static final List<String> TOPICS = List.of("transfers.check", "transfers.check.result",
            "transfers.check.result.DLT", "transfers.status-changed");

    static {
        KAFKA.start();
        createTopics();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    protected static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    private static void createTopics() {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(TOPICS.stream().map(name -> new NewTopic(name, 1, (short) 1)).toList())
                    .all().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Cannot create test topics", e);
        }
    }
}
