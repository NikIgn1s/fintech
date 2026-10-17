package dev.finflow.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.support.AbstractPostgresTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class OutboxPublisherTest extends AbstractPostgresTest {

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private record Sample(String transferId, String amount) {
    }

    @Test
    void storesEnvelopeInTheSameTransaction() throws Exception {
        String aggregateId = "agg-" + UUID.randomUUID();

        UUID eventId = new TransactionTemplate(transactionManager).execute(status ->
                publisher.publish("transfer", aggregateId, "TransferCheckRequested", "transfers.check",
                        new Sample(aggregateId, "100.0000")));

        Map<String, Object> row = jdbc.queryForMap("select * from outbox_events where id = ?", eventId);
        assertThat(row.get("aggregate_type")).isEqualTo("transfer");
        assertThat(row.get("aggregate_id")).isEqualTo(aggregateId);
        assertThat(row.get("event_type")).isEqualTo("TransferCheckRequested");
        assertThat(row.get("topic")).isEqualTo("transfers.check");
        assertThat(row.get("published_at")).isNull();
        JsonNode envelope = objectMapper.readTree(row.get("payload").toString());
        assertThat(envelope.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(envelope.get("eventType").asText()).isEqualTo("TransferCheckRequested");
        assertThat(envelope.get("version").asInt()).isEqualTo(1);
        assertThat(envelope.get("occurredAt").asText()).isNotBlank();
        assertThat(envelope.get("payload").get("transferId").asText()).isEqualTo(aggregateId);
        assertThat(envelope.get("payload").get("amount").asText()).isEqualTo("100.0000");
    }

    @Test
    void rollbackDiscardsTheEvent() {
        String aggregateId = "rollback-" + UUID.randomUUID();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publisher.publish("transfer", aggregateId, "TransferCheckRequested", "transfers.check",
                    new Sample(aggregateId, "1"));
            status.setRollbackOnly();
        });

        Integer count = jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?",
                Integer.class, aggregateId);
        assertThat(count).isZero();
    }

    @Test
    void cannotPublishOutsideATransaction() {
        assertThatThrownBy(() -> publisher.publish("transfer", "x", "E", "topic", new Sample("x", "1")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void eachEventGetsItsOwnIdentifier() {
        String aggregateId = "ids-" + UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        UUID first = tx.execute(status -> publisher.publish("transfer", aggregateId, "E", "t", new Sample("a", "1")));
        UUID second = tx.execute(status -> publisher.publish("transfer", aggregateId, "E", "t", new Sample("b", "2")));

        assertThat(first).isNotEqualTo(second);
    }
}
