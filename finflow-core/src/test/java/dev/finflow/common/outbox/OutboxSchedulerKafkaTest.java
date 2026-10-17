package dev.finflow.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.finflow.support.AbstractKafkaTest;
import dev.finflow.support.KafkaProbe;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "finflow.outbox.enabled=true",
        "finflow.outbox.poll-interval=200",
        "finflow.outbox.initial-delay=0"})
class OutboxSchedulerKafkaTest extends AbstractKafkaTest {

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private record Sample(int number) {
    }

    @Test
    void scheduledRelayDeliversEventsWithoutAnyManualTrigger() {
        String topic = "outbox-scheduler-" + UUID.randomUUID();
        UUID eventId = new TransactionTemplate(transactionManager).execute(status ->
                publisher.publish("transfer", "auto-1", "Sample", topic, new Sample(7)));

        try (KafkaProbe probe = new KafkaProbe(bootstrapServers(), topic)) {
            assertThat(probe.poll(1, Duration.ofSeconds(30))).hasSize(1);
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Object publishedAt = jdbc.queryForObject("select published_at from outbox_events where id = ?",
                    Object.class, eventId);
            assertThat(publishedAt).isNotNull();
        });
    }

    @Test
    void relayRecordsItsLockInShedlock() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer locks = jdbc.queryForObject("select count(*) from shedlock where name = 'outboxRelay'",
                    Integer.class);
            assertThat(locks).isEqualTo(1);
        });
    }
}
