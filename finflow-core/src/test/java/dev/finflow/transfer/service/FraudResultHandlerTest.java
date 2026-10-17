package dev.finflow.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.finflow.account.service.AccountService;
import dev.finflow.auth.repository.UserRepository;
import dev.finflow.common.messaging.InvalidEventException;
import dev.finflow.common.outbox.EventEnvelope;
import dev.finflow.ledger.service.CashService;
import dev.finflow.support.AbstractPostgresTest;
import dev.finflow.transfer.messaging.FraudFlowFixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class FraudResultHandlerTest extends AbstractPostgresTest {

    @Autowired
    private FraudResultHandler handler;

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    private FraudFlowFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new FraudFlowFixtures(users, accounts, cash, transfers, jdbc, objectMapper);
    }

    private EventEnvelope envelope(ObjectNode node) throws Exception {
        return objectMapper.treeToValue(node, EventEnvelope.class);
    }

    private int processedCount(UUID eventId) {
        return fixtures.count("select count(*) from processed_events where event_id = ? and consumer = 'fraud-result'",
                eventId);
    }

    @Test
    void appliesVerdictAndRemembersTheEvent() throws Exception {
        var pending = fixtures.pendingTransfer("100");
        UUID eventId = UUID.randomUUID();

        handler.handle(envelope(fixtures.resultEnvelope(eventId, pending.transfer().id(), "APPROVE", "0.2", List.of())));

        assertThat(fixtures.status(pending.transfer().id())).isEqualTo("COMPLETED");
        assertThat(processedCount(eventId)).isEqualTo(1);
    }

    @Test
    void redeliveredEventIsAppliedOnlyOnce() throws Exception {
        var pending = fixtures.pendingTransfer("100");
        UUID id = pending.transfer().id();
        UUID eventId = UUID.randomUUID();

        handler.handle(envelope(fixtures.resultEnvelope(eventId, id, "APPROVE", "0.2", List.of())));
        handler.handle(envelope(fixtures.resultEnvelope(eventId, id, "HOLD", "0.9", List.of("different"))));

        assertThat(fixtures.status(id)).isEqualTo("COMPLETED");
        assertThat(fixtures.settleCount(id)).isEqualTo(1);
        assertThat(fixtures.fraudCheckCount(id)).isEqualTo(1);
        assertThat(processedCount(eventId)).isEqualTo(1);
    }

    @Test
    void laterResultWithAnotherEventIdDoesNotOverrideTheDecision() throws Exception {
        var pending = fixtures.pendingTransfer("100");
        UUID id = pending.transfer().id();

        handler.handle(envelope(fixtures.resultEnvelope(UUID.randomUUID(), id, "HOLD", "0.9", List.of("risky"))));
        UUID lateEvent = UUID.randomUUID();
        handler.handle(envelope(fixtures.resultEnvelope(lateEvent, id, "APPROVE", "0.1", List.of())));

        assertThat(fixtures.status(id)).isEqualTo("ON_HOLD");
        assertThat(fixtures.settleCount(id)).isZero();
        assertThat(processedCount(lateEvent)).isEqualTo(1);
    }

    @Test
    void processingMarkerIsPartOfTheBusinessTransaction() throws Exception {
        var pending = fixtures.pendingTransfer("100");
        UUID id = pending.transfer().id();
        UUID eventId = UUID.randomUUID();
        EventEnvelope event = envelope(fixtures.resultEnvelope(eventId, id, "APPROVE", "0.2", List.of()));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            handler.handle(event);
            status.setRollbackOnly();
        });

        assertThat(processedCount(eventId)).isZero();
        assertThat(fixtures.status(id)).isEqualTo("PENDING_CHECK");
        handler.handle(event);
        assertThat(fixtures.status(id)).isEqualTo("COMPLETED");
    }

    @Test
    void rejectsEventsWithoutIdentifier() throws Exception {
        var pending = fixtures.pendingTransfer("10");

        assertThatThrownBy(() -> handler.handle(
                envelope(fixtures.resultEnvelope(null, pending.transfer().id(), "APPROVE", "0.2", List.of()))))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void rejectsUnexpectedEventType() throws Exception {
        var pending = fixtures.pendingTransfer("10");
        ObjectNode node = fixtures.resultEnvelope(UUID.randomUUID(), pending.transfer().id(), "APPROVE", "0.2",
                List.of());
        node.put("eventType", "SomethingElse");

        assertThatThrownBy(() -> handler.handle(envelope(node))).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void rejectsScoreOutsideUnitInterval() throws Exception {
        var pending = fixtures.pendingTransfer("10");
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> handler.handle(
                envelope(fixtures.resultEnvelope(eventId, pending.transfer().id(), "APPROVE", "1.5", List.of()))))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> handler.handle(
                envelope(fixtures.resultEnvelope(eventId, pending.transfer().id(), "APPROVE", "-0.1", List.of()))))
                .isInstanceOf(InvalidEventException.class);
        assertThat(processedCount(eventId)).isZero();
        assertThat(fixtures.status(pending.transfer().id())).isEqualTo("PENDING_CHECK");
    }

    @Test
    void rejectsMissingFieldsAndUnknownDecision() throws Exception {
        var pending = fixtures.pendingTransfer("10");
        UUID id = pending.transfer().id();

        assertThatThrownBy(() -> handler.handle(
                envelope(fixtures.resultEnvelope(UUID.randomUUID(), null, "APPROVE", "0.2", List.of()))))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> handler.handle(
                envelope(fixtures.resultEnvelope(UUID.randomUUID(), id, null, "0.2", List.of()))))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> handler.handle(
                envelope(fixtures.resultEnvelope(UUID.randomUUID(), id, "APPROVE", null, List.of()))))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> handler.handle(
                envelope(fixtures.resultEnvelope(UUID.randomUUID(), id, "MAYBE", "0.2", List.of()))))
                .isInstanceOf(InvalidEventException.class);
        assertThat(fixtures.status(id)).isEqualTo("PENDING_CHECK");
    }
}
