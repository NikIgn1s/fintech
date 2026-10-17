package dev.finflow.transfer.fraud;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.common.messaging.EventTypes;
import dev.finflow.common.outbox.EventEnvelope;
import dev.finflow.common.outbox.OutboxEvent;
import dev.finflow.common.outbox.OutboxRepository;
import dev.finflow.transfer.domain.FraudDecision;
import dev.finflow.transfer.domain.FraudSource;
import dev.finflow.transfer.domain.Transfer;
import dev.finflow.transfer.domain.TransferStatus;
import dev.finflow.transfer.messaging.TransferCheckRequested;
import dev.finflow.transfer.repository.TransferRepository;
import dev.finflow.transfer.service.FraudDecisionService;
import dev.finflow.transfer.service.FraudVerdict;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class FraudFallbackWorker {

    private final TransferRepository transfers;
    private final OutboxRepository outbox;
    private final FraudRules rules;
    private final FraudDecisionService decisions;
    private final ObjectMapper objectMapper;

    @Transactional
    public boolean resolve(UUID transferId) {
        Transfer transfer = transfers.findById(transferId).orElse(null);
        if (transfer == null || transfer.getStatus() != TransferStatus.PENDING_CHECK) {
            return false;
        }
        Optional<JsonNode> features = loadFeatures(transferId);
        FraudVerdict verdict = features.map(json -> verdictFor(transferId, json))
                .orElseGet(() -> new FraudVerdict(transferId, FraudDecision.HOLD, FraudSource.RULES_FALLBACK, null,
                        null, List.of("Check features are unavailable"), null));
        decisions.apply(verdict);
        return true;
    }

    private FraudVerdict verdictFor(UUID transferId, JsonNode payload) {
        try {
            TransferCheckRequested features = objectMapper.treeToValue(payload, TransferCheckRequested.class);
            FraudRules.Result result = rules.evaluate(features);
            return new FraudVerdict(transferId, result.decision(), FraudSource.RULES_FALLBACK, null, null,
                    result.reasons(), payload.toString());
        } catch (JsonProcessingException e) {
            log.warn("Cannot read stored check features of transfer {}", transferId, e);
            return new FraudVerdict(transferId, FraudDecision.HOLD, FraudSource.RULES_FALLBACK, null, null,
                    List.of("Check features are unreadable"), null);
        }
    }

    private Optional<JsonNode> loadFeatures(UUID transferId) {
        return outbox.findFirstByAggregateIdAndEventTypeOrderByCreatedAtDesc(transferId.toString(),
                        EventTypes.TRANSFER_CHECK_REQUESTED)
                .map(OutboxEvent::getPayload)
                .map(this::envelopePayload);
    }

    private JsonNode envelopePayload(String json) {
        try {
            return objectMapper.readValue(json, EventEnvelope.class).payload();
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
