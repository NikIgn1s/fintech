package dev.finflow.transfer.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.account.service.AccountService;
import dev.finflow.auth.repository.UserRepository;
import dev.finflow.ledger.service.CashService;
import dev.finflow.support.AbstractPostgresTest;
import dev.finflow.transfer.messaging.FraudFlowFixtures;
import dev.finflow.transfer.service.TransferService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class FraudFallbackServiceTest extends AbstractPostgresTest {

    @Autowired
    private FraudFallbackService fallback;

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

    private void makeStale(UUID transferId) {
        jdbc.update("update transfers set created_at = now() - interval '5 minutes' where id = ?", transferId);
    }

    @Test
    void approvesSafeTransferThatWaitedForTooLong() {
        var pending = fixtures.pendingTransfer("100");
        UUID id = pending.transfer().id();
        makeStale(id);

        fallback.resolveStale();

        assertThat(fixtures.status(id)).isEqualTo("COMPLETED");
        assertThat(fixtures.balance(pending.to().id())).isEqualByComparingTo("100");
        Map<String, Object> check = jdbc.queryForMap("select * from fraud_checks where transfer_id = ?", id);
        assertThat(check.get("source")).isEqualTo("RULES_FALLBACK");
        assertThat(check.get("decision")).isEqualTo("APPROVE");
        assertThat(check.get("score")).isNull();
        assertThat(check.get("features").toString()).contains("fromAccountAgeDays");
    }

    @Test
    void holdsRiskyTransferWithTheMatchedRuleAsReason() {
        var pending = fixtures.pendingTransfer("60000");
        UUID id = pending.transfer().id();
        makeStale(id);

        fallback.resolveStale();

        assertThat(fixtures.status(id)).isEqualTo("ON_HOLD");
        assertThat(jdbc.queryForObject("select hold_reason from transfers where id = ?", String.class, id))
                .isEqualTo("Large amount from a new account");
        assertThat(fixtures.balance(pending.to().id())).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select decision from fraud_checks where transfer_id = ?", String.class, id))
                .isEqualTo("HOLD");
    }

    @Test
    void leavesFreshTransfersToTheMlService() {
        var pending = fixtures.pendingTransfer("100");

        fallback.resolveStale();

        assertThat(fixtures.status(pending.transfer().id())).isEqualTo("PENDING_CHECK");
        assertThat(fixtures.fraudCheckCount(pending.transfer().id())).isZero();
    }

    @Test
    void holdsWhenStoredFeaturesAreGone() {
        var pending = fixtures.pendingTransfer("100");
        UUID id = pending.transfer().id();
        jdbc.update("delete from outbox_events where aggregate_id = ?", id.toString());
        makeStale(id);

        fallback.resolveStale();

        assertThat(fixtures.status(id)).isEqualTo("ON_HOLD");
        assertThat(jdbc.queryForObject("select hold_reason from transfers where id = ?", String.class, id))
                .isEqualTo("Check features are unavailable");
    }

    @Test
    void secondPassDoesNotTouchResolvedTransfers() {
        var pending = fixtures.pendingTransfer("100");
        UUID id = pending.transfer().id();
        makeStale(id);
        fallback.resolveStale();

        fallback.resolveStale();

        assertThat(fixtures.fraudCheckCount(id)).isEqualTo(1);
        assertThat(fixtures.settleCount(id)).isEqualTo(1);
    }
}
