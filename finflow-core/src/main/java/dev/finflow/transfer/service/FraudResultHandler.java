package dev.finflow.transfer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.common.messaging.EventTypes;
import dev.finflow.common.messaging.InvalidEventException;
import dev.finflow.common.messaging.ProcessedEventStore;
import dev.finflow.common.outbox.EventEnvelope;
import dev.finflow.transfer.domain.FraudSource;
import dev.finflow.transfer.messaging.FraudCheckResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudResultHandler {

    public static final String CONSUMER = "fraud-result";

    private static final int SCORE_SCALE = 4;

    private final ProcessedEventStore processedEvents;
    private final FraudDecisionService decisions;
    private final ObjectMapper objectMapper;

    @Transactional
    public void handle(EventEnvelope envelope) {
        FraudCheckResult result = parse(envelope);
        if (!processedEvents.markProcessed(envelope.eventId(), CONSUMER)) {
            log.info("Duplicate fraud result event {} skipped", envelope.eventId());
            return;
        }
        decisions.apply(new FraudVerdict(result.transferId(), result.decision(), FraudSource.ML,
                result.score().setScale(SCORE_SCALE, RoundingMode.HALF_UP), result.modelVersion(),
                result.reasons(), null));
    }

    private FraudCheckResult parse(EventEnvelope envelope) {
        if (envelope.eventId() == null || envelope.payload() == null) {
            throw new InvalidEventException("Fraud result event has no eventId or payload");
        }
        if (!EventTypes.FRAUD_CHECK_COMPLETED.equals(envelope.eventType())) {
            throw new InvalidEventException("Unexpected event type " + envelope.eventType());
        }
        FraudCheckResult result;
        try {
            result = objectMapper.treeToValue(envelope.payload(), FraudCheckResult.class);
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("Fraud result payload cannot be read", e);
        }
        if (result.transferId() == null || result.decision() == null || result.score() == null) {
            throw new InvalidEventException("Fraud result requires transferId, decision and score");
        }
        if (result.score().compareTo(BigDecimal.ZERO) < 0 || result.score().compareTo(BigDecimal.ONE) > 0) {
            throw new InvalidEventException("Fraud score " + result.score() + " is outside [0, 1]");
        }
        return result;
    }
}
