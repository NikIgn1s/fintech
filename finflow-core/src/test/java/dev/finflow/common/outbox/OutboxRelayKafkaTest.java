package dev.finflow.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.support.AbstractKafkaTest;
import dev.finflow.support.KafkaProbe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@TestPropertySource(properties = "finflow.outbox.batch-size=5")
class OutboxRelayKafkaTest extends AbstractKafkaTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxBatchPublisher batchPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private record Sample(int number) {
    }

    private String newTopic() {
        return "outbox-test-" + UUID.randomUUID();
    }

    private UUID publish(String topic, String key, int number) {
        return new TransactionTemplate(transactionManager).execute(status ->
                publisher.publish("transfer", key, "Sample", topic, new Sample(number)));
    }

    private int pending(String topic) {
        return jdbc.queryForObject("select count(*) from outbox_events where topic = ? and published_at is null",
                Integer.class, topic);
    }

    private void drain(String topic) {
        while (pending(topic) > 0) {
            batchPublisher.publishBatch();
        }
    }

    @Test
    void deliversEventWithKeyHeadersAndEnvelope() throws Exception {
        String topic = newTopic();
        UUID eventId = publish(topic, "transfer-1", 1);

        drain(topic);

        try (KafkaProbe probe = new KafkaProbe(bootstrapServers(), topic)) {
            List<ConsumerRecord<String, String>> records = probe.poll(1, TIMEOUT);
            assertThat(records).hasSize(1);
            ConsumerRecord<String, String> record = records.get(0);
            assertThat(record.key()).isEqualTo("transfer-1");
            assertThat(new String(record.headers().lastHeader("eventId").value())).isEqualTo(eventId.toString());
            assertThat(new String(record.headers().lastHeader("eventType").value())).isEqualTo("Sample");
            JsonNode envelope = JSON.readTree(record.value());
            assertThat(envelope.get("eventId").asText()).isEqualTo(eventId.toString());
            assertThat(envelope.get("payload").get("number").asInt()).isEqualTo(1);
        }
        Object publishedAt = jdbc.queryForObject("select published_at from outbox_events where id = ?",
                Object.class, eventId);
        assertThat(publishedAt).isNotNull();
    }

    @Test
    void keepsOrderOfEventsForTheSameKey() throws Exception {
        String topic = newTopic();
        for (int i = 1; i <= 12; i++) {
            publish(topic, "same-aggregate", i);
        }

        drain(topic);

        try (KafkaProbe probe = new KafkaProbe(bootstrapServers(), topic)) {
            List<ConsumerRecord<String, String>> records = probe.poll(12, TIMEOUT);
            assertThat(records).hasSize(12);
            List<Integer> numbers = new ArrayList<>();
            for (ConsumerRecord<String, String> record : records) {
                numbers.add(JSON.readTree(record.value()).get("payload").get("number").asInt());
            }
            assertThat(numbers).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        }
    }

    @Test
    void publishesAtMostBatchSizeEventsPerCall() {
        String topic = newTopic();
        for (int i = 1; i <= 12; i++) {
            publish(topic, "k-" + i, i);
        }

        assertThat(batchPublisher.publishBatch()).isLessThanOrEqualTo(5);
        assertThat(pending(topic)).isGreaterThanOrEqualTo(7);
        drain(topic);
        assertThat(pending(topic)).isZero();
    }

    @Test
    void doesNotPublishWhenTheBusinessTransactionRollsBack() {
        String topic = newTopic();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publisher.publish("transfer", "ghost", "Sample", topic, new Sample(1));
            status.setRollbackOnly();
        });

        drain(topic);

        try (KafkaProbe probe = new KafkaProbe(bootstrapServers(), topic)) {
            assertThat(probe.drain(Duration.ofSeconds(2))).isEmpty();
        }
    }

    @Test
    void concurrentRelaysNeverPublishTheSameEventTwice() throws Exception {
        String topic = newTopic();
        int events = 40;
        for (int i = 1; i <= events; i++) {
            publish(topic, "k-" + i, i);
        }

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> workers = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            workers.add(pool.submit(() -> {
                start.await();
                while (pending(topic) > 0) {
                    batchPublisher.publishBatch();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> worker : workers) {
            worker.get();
        }
        pool.shutdownNow();

        try (KafkaProbe probe = new KafkaProbe(bootstrapServers(), topic)) {
            List<ConsumerRecord<String, String>> records = probe.poll(events, TIMEOUT);
            Set<String> keys = new HashSet<>();
            records.forEach(record -> keys.add(record.key()));
            assertThat(records).hasSize(events);
            assertThat(keys).hasSize(events);
            assertThat(probe.drain(Duration.ofSeconds(2))).isEmpty();
        }
    }
}
