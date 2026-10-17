package dev.finflow.transfer.service;

import dev.finflow.transfer.domain.FraudDecision;
import dev.finflow.transfer.domain.FraudSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record FraudVerdict(
        UUID transferId,
        FraudDecision decision,
        FraudSource source,
        BigDecimal score,
        String modelVersion,
        List<String> reasons,
        String featuresJson) {

    public List<String> reasons() {
        return reasons == null ? List.of() : reasons;
    }
}
