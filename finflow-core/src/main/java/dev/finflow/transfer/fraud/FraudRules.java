package dev.finflow.transfer.fraud;

import dev.finflow.transfer.domain.FraudDecision;
import dev.finflow.transfer.messaging.TransferCheckRequested;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FraudRules {

    public record Result(FraudDecision decision, List<String> reasons) {
    }

    private final FraudRulesProperties properties;

    public Result evaluate(TransferCheckRequested features) {
        List<String> reasons = new ArrayList<>();
        if (features.amount().compareTo(properties.largeAmountNewAccount()) > 0
                && features.fromAccountAgeDays() < properties.newAccountDays()) {
            reasons.add("Large amount from a new account");
        }
        if (features.transfersLast1h() >= properties.maxTransfersPerHour()) {
            reasons.add("Too many transfers in the last hour");
        }
        if (features.hourOfDay() < properties.nightEndHour()
                && features.isFirstTransferToRecipient()
                && features.amount().compareTo(properties.nightAmount()) > 0) {
            reasons.add("Large night transfer to a new recipient");
        }
        FraudDecision decision = reasons.isEmpty() ? FraudDecision.APPROVE : FraudDecision.HOLD;
        return new Result(decision, List.copyOf(reasons));
    }
}
