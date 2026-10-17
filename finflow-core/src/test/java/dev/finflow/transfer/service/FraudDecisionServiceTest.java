package dev.finflow.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.account.service.AccountService;
import dev.finflow.auth.repository.UserRepository;
import dev.finflow.ledger.service.CashService;
import dev.finflow.support.AbstractPostgresTest;
import dev.finflow.transfer.domain.FraudDecision;
import dev.finflow.transfer.domain.FraudSource;
import dev.finflow.transfer.messaging.FraudFlowFixtures;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class FraudDecisionServiceTest extends AbstractPostgresTest {

    @Autowired
    private FraudDecisionService decisions;

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

    private FraudVerdict verdict(UUID transferId, FraudDecision decision, String score, List<String> reasons) {
        return new FraudVerdict(transferId, decision, FraudSource.ML, new BigDecimal(score), "model-7", reasons, null);
    }

    @Test
    void approvalSettlesMoneyToTheRecipientAndCompletesTheTransfer() throws Exception {
        var pending = fixtures.pendingTransfer("300.50");
        UUID id = pending.transfer().id();

        var outcome = decisions.apply(verdict(id, FraudDecision.APPROVE, "0.1200", List.of()));

        assertThat(outcome).isEqualTo(FraudDecisionService.Outcome.APPLIED);
        assertThat(fixtures.status(id)).isEqualTo("COMPLETED");
        assertThat(fixtures.balance(pending.to().id())).isEqualByComparingTo("300.50");
        assertThat(fixtures.balance(pending.from().id())).isEqualByComparingTo("99699.50");
        assertThat(fixtures.settleCount(id)).isEqualTo(1);
        Map<String, Object> check = jdbc.queryForMap("select * from fraud_checks where transfer_id = ?", id);
        assertThat(check.get("decision")).isEqualTo("APPROVE");
        assertThat(check.get("source")).isEqualTo("ML");
        assertThat(check.get("model_version")).isEqualTo("model-7");
        assertThat((BigDecimal) check.get("score")).isEqualByComparingTo("0.12");
        assertThat(jdbc.queryForObject("select fraud_score from transfers where id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("0.12");
    }

    @Test
    void approvalPublishesStatusChange() throws Exception {
        var pending = fixtures.pendingTransfer("10");
        UUID id = pending.transfer().id();

        decisions.apply(verdict(id, FraudDecision.APPROVE, "0.05", List.of()));

        String json = jdbc.queryForObject("select payload::text from outbox_events "
                + "where topic = 'transfers.status-changed' and aggregate_id = ?", String.class, id.toString());
        JsonNode payload = objectMapper.readTree(json).get("payload");
        assertThat(payload.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(payload.get("transferId").asText()).isEqualTo(id.toString());
        assertThat(payload.get("amount").asText()).isEqualTo("10.0000");
        assertThat(payload.get("decidedBy").asText()).isEqualTo("ML");
    }

    @Test
    void holdKeepsMoneyInSuspenseAndRecordsReasons() {
        var pending = fixtures.pendingTransfer("500");
        UUID id = pending.transfer().id();

        decisions.apply(verdict(id, FraudDecision.HOLD, "0.9100",
                List.of("amount far above average", "night transfer")));

        assertThat(fixtures.status(id)).isEqualTo("ON_HOLD");
        assertThat(jdbc.queryForObject("select hold_reason from transfers where id = ?", String.class, id))
                .isEqualTo("amount far above average; night transfer");
        assertThat(fixtures.balance(pending.to().id())).isEqualByComparingTo("0");
        assertThat(fixtures.settleCount(id)).isZero();
        assertThat(jdbc.queryForObject("select decision from fraud_checks where transfer_id = ?", String.class, id))
                .isEqualTo("HOLD");
        assertThat(fixtures.statusChangedEvents(id)).isEqualTo(1);
    }

    @Test
    void holdWithoutReasonsFallsBackToScore() {
        var pending = fixtures.pendingTransfer("5");
        UUID id = pending.transfer().id();

        decisions.apply(verdict(id, FraudDecision.HOLD, "0.8800", null));

        assertThat(jdbc.queryForObject("select hold_reason from transfers where id = ?", String.class, id))
                .isEqualTo("Fraud score 0.8800");
    }

    @Test
    void longReasonsAreTruncatedToColumnSize() {
        var pending = fixtures.pendingTransfer("5");
        UUID id = pending.transfer().id();

        decisions.apply(verdict(id, FraudDecision.HOLD, "0.9", List.of("x".repeat(400))));

        assertThat(jdbc.queryForObject("select length(hold_reason) from transfers where id = ?", Integer.class, id))
                .isEqualTo(255);
    }

    @Test
    void verdictForResolvedTransferIsIgnored() {
        var pending = fixtures.pendingTransfer("20");
        UUID id = pending.transfer().id();
        decisions.apply(verdict(id, FraudDecision.APPROVE, "0.1", List.of()));

        var outcome = decisions.apply(verdict(id, FraudDecision.HOLD, "0.95", List.of("late")));

        assertThat(outcome).isEqualTo(FraudDecisionService.Outcome.ALREADY_RESOLVED);
        assertThat(fixtures.status(id)).isEqualTo("COMPLETED");
        assertThat(fixtures.fraudCheckCount(id)).isEqualTo(1);
        assertThat(fixtures.statusChangedEvents(id)).isEqualTo(1);
        assertThat(fixtures.settleCount(id)).isEqualTo(1);
    }

    @Test
    void verdictForUnknownTransferIsIgnored() {
        var outcome = decisions.apply(verdict(UUID.randomUUID(), FraudDecision.APPROVE, "0.1", List.of()));

        assertThat(outcome).isEqualTo(FraudDecisionService.Outcome.UNKNOWN_TRANSFER);
    }
}
