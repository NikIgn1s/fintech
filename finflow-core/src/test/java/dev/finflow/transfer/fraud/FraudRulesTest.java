package dev.finflow.transfer.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finflow.transfer.domain.FraudDecision;
import dev.finflow.transfer.messaging.TransferCheckRequested;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FraudRulesTest {

    private final FraudRules rules = new FraudRules(new FraudRulesProperties(
            new BigDecimal("50000"), 7, 10, new BigDecimal("20000"), 6));

    private TransferCheckRequested features(String amount, long accountAgeDays, boolean firstToRecipient,
            long transfersLast1h, int hour) {
        return new TransferCheckRequested(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal(amount), "RUB", accountAgeDays, firstToRecipient, transfersLast1h, transfersLast1h,
                BigDecimal.ZERO, BigDecimal.ZERO, hour, 100, 0, Instant.now());
    }

    @Test
    void ordinaryTransferIsApproved() {
        var result = rules.evaluate(features("1000", 30, false, 1, 14));

        assertThat(result.decision()).isEqualTo(FraudDecision.APPROVE);
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void largeAmountFromNewAccountIsHeld() {
        var result = rules.evaluate(features("50000.01", 3, false, 0, 14));

        assertThat(result.decision()).isEqualTo(FraudDecision.HOLD);
        assertThat(result.reasons()).containsExactly("Large amount from a new account");
    }

    @Test
    void boundaryAmountOrOldAccountIsApproved() {
        assertThat(rules.evaluate(features("50000", 3, false, 0, 14)).decision()).isEqualTo(FraudDecision.APPROVE);
        assertThat(rules.evaluate(features("90000", 7, false, 0, 14)).decision()).isEqualTo(FraudDecision.APPROVE);
    }

    @Test
    void tenthTransferWithinAnHourIsHeld() {
        assertThat(rules.evaluate(features("10", 30, false, 9, 14)).decision()).isEqualTo(FraudDecision.APPROVE);
        assertThat(rules.evaluate(features("10", 30, false, 10, 14)).decision()).isEqualTo(FraudDecision.HOLD);
    }

    @Test
    void largeNightTransferToNewRecipientIsHeld() {
        var result = rules.evaluate(features("20000.01", 30, true, 0, 3));

        assertThat(result.decision()).isEqualTo(FraudDecision.HOLD);
        assertThat(result.reasons()).containsExactly("Large night transfer to a new recipient");
    }

    @Test
    void nightRuleNeedsAllThreeConditions() {
        assertThat(rules.evaluate(features("20000.01", 30, false, 0, 3)).decision()).isEqualTo(FraudDecision.APPROVE);
        assertThat(rules.evaluate(features("20000", 30, true, 0, 3)).decision()).isEqualTo(FraudDecision.APPROVE);
        assertThat(rules.evaluate(features("30000", 30, true, 0, 6)).decision()).isEqualTo(FraudDecision.APPROVE);
        assertThat(rules.evaluate(features("30000", 30, true, 0, 5)).decision()).isEqualTo(FraudDecision.HOLD);
    }

    @Test
    void collectsEveryMatchedReason() {
        var result = rules.evaluate(features("60000", 1, true, 12, 2));

        assertThat(result.decision()).isEqualTo(FraudDecision.HOLD);
        assertThat(result.reasons()).hasSize(3);
    }
}
