package dev.finflow.transfer.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.finflow.transfer.domain.FraudDecision;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FraudCheckResult(
        UUID transferId,
        BigDecimal score,
        FraudDecision decision,
        List<String> reasons,
        String modelVersion) {
}
