package dev.finflow.transfer.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.account.service.AccountService;
import dev.finflow.auth.repository.UserRepository;
import dev.finflow.common.messaging.Topics;
import dev.finflow.common.outbox.OutboxBatchPublisher;
import dev.finflow.ledger.service.CashService;
import dev.finflow.support.AbstractKafkaTest;
import dev.finflow.support.KafkaProbe;
import dev.finflow.transfer.service.TransferService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "finflow.kafka.listeners.enabled=true")
class FraudResultKafkaTest extends AbstractKafkaTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private OutboxBatchPublisher batchPublisher;

    @Autowired
    private UserRepository users;

    @Autowired
    private AccountService accounts;

    @Autowired
    private CashService cash;

    @Autowired
    private TransferService transfers;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private FraudFlowFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new FraudFlowFixtures(users, accounts, cash, transfers, jdbc, objectMapper);
    }

    private void sendResult(UUID transferId, UUID eventId, String decision, String score, List<String> reasons)
            throws Exception {
        String json = objectMapper.writeValueAsString(
                fixtures.resultEnvelope(eventId, transferId, decision, score, reasons));
        kafka.send(Topics.TRANSFERS_CHECK_RESULT, transferId.toString(), json).get();
    }

    private void awaitStatus(UUID transferId, String expected) {
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(fixtures.status(transferId)).isEqualTo(expected));
    }

    private void relayOutbox() {
        int guard = 0;
        while (jdbc.queryForObject("select count(*) from outbox_events where published_at is null", Integer.class) > 0
                && guard++ < 200) {
            batchPublisher.publishBatch();
        }
    }

    private JsonNode awaitCheckRequest(UUID transferId) throws Exception {
        try (KafkaProbe probe = new KafkaProbe(bootstrapServers(), Topics.TRANSFERS_CHECK)) {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : probe.poll(1, Duration.ofSeconds(2))) {
                    if (transferId.toString().equals(record.key())) {
                        return objectMapper.readTree(record.value());
                    }
                }
            }
        }
        throw new AssertionError("No check request published for " + transferId);
    }

    @Test
    void endToEndApprovalMovesMoneyToTheRecipient() throws Exception {
        var pending = fixtures.pendingTransfer("750");
        UUID id = pending.transfer().id();

        relayOutbox();
        JsonNode request = awaitCheckRequest(id);
        assertThat(request.get("eventType").asText()).isEqualTo("TransferCheckRequested");
        assertThat(request.get("payload").get("transferId").asText()).isEqualTo(id.toString());
        assertThat(fixtures.status(id)).isEqualTo("PENDING_CHECK");

        sendResult(id, UUID.randomUUID(), "APPROVE", "0.0300", List.of());

        awaitStatus(id, "COMPLETED");
        assertThat(fixtures.balance(pending.to().id())).isEqualByComparingTo("750");
        assertThat(fixtures.fraudCheckCount(id)).isEqualTo(1);
        assertThat(fixtures.settleCount(id)).isEqualTo(1);
    }

    @Test
    void holdVerdictParksTheTransfer() throws Exception {
        var pending = fixtures.pendingTransfer("900");
        UUID id = pending.transfer().id();

        sendResult(id, UUID.randomUUID(), "HOLD", "0.9400", List.of("new recipient", "large amount"));

        awaitStatus(id, "ON_HOLD");
        assertThat(fixtures.balance(pending.to().id())).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select hold_reason from transfers where id = ?", String.class, id))
                .isEqualTo("new recipient; large amount");
    }

    @Test
    void duplicateDeliveryOfTheSameEventSettlesOnce() throws Exception {
        var pending = fixtures.pendingTransfer("120");
        var marker = fixtures.pendingTransfer("1");
        UUID id = pending.transfer().id();
        UUID eventId = UUID.randomUUID();

        sendResult(id, eventId, "APPROVE", "0.1000", List.of());
        sendResult(id, eventId, "APPROVE", "0.1000", List.of());
        sendResult(marker.transfer().id(), UUID.randomUUID(), "APPROVE", "0.1000", List.of());

        awaitStatus(marker.transfer().id(), "COMPLETED");
        assertThat(fixtures.status(id)).isEqualTo("COMPLETED");
        assertThat(fixtures.settleCount(id)).isEqualTo(1);
        assertThat(fixtures.fraudCheckCount(id)).isEqualTo(1);
        assertThat(fixtures.count("select count(*) from processed_events where event_id = ?", eventId)).isEqualTo(1);
    }

    @Test
    void lateConflictingResultIsIgnored() throws Exception {
        var pending = fixtures.pendingTransfer("60");
        var marker = fixtures.pendingTransfer("1");
        UUID id = pending.transfer().id();

        sendResult(id, UUID.randomUUID(), "APPROVE", "0.1000", List.of());
        sendResult(id, UUID.randomUUID(), "HOLD", "0.9900", List.of("too late"));
        sendResult(marker.transfer().id(), UUID.randomUUID(), "APPROVE", "0.1000", List.of());

        awaitStatus(marker.transfer().id(), "COMPLETED");
        assertThat(fixtures.status(id)).isEqualTo("COMPLETED");
        assertThat(fixtures.fraudCheckCount(id)).isEqualTo(1);
    }

    @Test
    void invalidMessagesGoStraightToTheDeadLetterTopic() throws Exception {
        String malformedKey = "malformed-" + UUID.randomUUID();
        String semanticKey = "semantic-" + UUID.randomUUID();
        String semanticValue = objectMapper.writeValueAsString(
                fixtures.resultEnvelope(UUID.randomUUID(), UUID.randomUUID(), "APPROVE", "5", List.of()));

        kafka.send(Topics.TRANSFERS_CHECK_RESULT, malformedKey, "this is not json").get();
        kafka.send(Topics.TRANSFERS_CHECK_RESULT, semanticKey, semanticValue).get();

        try (KafkaProbe probe = new KafkaProbe(bootstrapServers(), Topics.TRANSFERS_CHECK_RESULT + ".DLT")) {
            Map<String, ConsumerRecord<String, String>> parked = new HashMap<>();
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (parked.size() < 2 && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : probe.poll(1, Duration.ofSeconds(2))) {
                    parked.put(record.key(), record);
                }
            }
            assertThat(parked).containsKeys(malformedKey, semanticKey);
            assertThat(parked.get(malformedKey).value()).isEqualTo("this is not json");
            assertThat(parked.get(semanticKey).value()).isEqualTo(semanticValue);
            String exception = new String(parked.get(malformedKey).headers()
                    .lastHeader("kafka_dlt-exception-fqcn").value());
            assertThat(exception).contains("ListenerExecutionFailedException");
            assertThat(new String(parked.get(malformedKey).headers().lastHeader("kafka_dlt-original-topic").value()))
                    .isEqualTo(Topics.TRANSFERS_CHECK_RESULT);
        }
    }

    @Test
    void poisonMessageDoesNotBlockTheConsumer() throws Exception {
        var pending = fixtures.pendingTransfer("30");
        kafka.send(Topics.TRANSFERS_CHECK_RESULT, "garbage", "this is not json").get();
        kafka.send(Topics.TRANSFERS_CHECK_RESULT, "garbage", "{\"eventType\":\"FraudCheckCompleted\"}").get();

        sendResult(pending.transfer().id(), UUID.randomUUID(), "APPROVE", "0.2000", List.of());

        awaitStatus(pending.transfer().id(), "COMPLETED");
        assertThat(fixtures.balance(pending.to().id())).isEqualByComparingTo(new BigDecimal("30"));
    }
}
