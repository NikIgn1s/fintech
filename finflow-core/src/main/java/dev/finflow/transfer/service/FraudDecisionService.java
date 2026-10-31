package dev.finflow.transfer.service;

import dev.finflow.transfer.domain.FraudCheck;
import dev.finflow.transfer.domain.Transfer;
import dev.finflow.transfer.domain.TransferStatus;
import dev.finflow.transfer.repository.FraudCheckRepository;
import dev.finflow.transfer.repository.TransferRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudDecisionService {

    private static final int MAX_REASON_LENGTH = 255;

    public enum Outcome {
        APPLIED,
        UNKNOWN_TRANSFER,
        ALREADY_RESOLVED
    }

    private final TransferRepository transfers;
    private final FraudCheckRepository fraudChecks;
    private final TransferSettlement settlement;
    private final TransferEventPublisher events;
    private final Clock clock;
    private final org.springframework.context.ApplicationEventPublisher metricsEvents;

    @Transactional
    public Outcome apply(FraudVerdict verdict) {
        Transfer transfer = transfers.findById(verdict.transferId()).orElse(null);
        if (transfer == null) {
            log.warn("Fraud verdict for unknown transfer {} ignored", verdict.transferId());
            return Outcome.UNKNOWN_TRANSFER;
        }
        if (transfer.getStatus() != TransferStatus.PENDING_CHECK) {
            log.info("Fraud verdict from {} for transfer {} ignored, transfer is already {}",
                    verdict.source(), transfer.getId(), transfer.getStatus());
            return Outcome.ALREADY_RESOLVED;
        }

        Instant now = clock.instant();
        switch (verdict.decision()) {
            case APPROVE -> {
                settlement.settle(transfer);
                transfer.complete(verdict.score(), now);
            }
            case HOLD -> transfer.hold(holdReason(verdict), verdict.score(), now);
        }
        fraudChecks.save(FraudCheck.record(transfer.getId(), verdict.score(), verdict.modelVersion(),
                verdict.decision(), verdict.source(), verdict.featuresJson(), now));
        events.statusChanged(transfer, verdict.source().name(), now);
        metricsEvents.publishEvent(new dev.finflow.transfer.api.events.TransferChecked(
                verdict.decision().name(),verdict.source().name(),java.time.Duration.between(transfer.getCreatedAt(),now)));
        return Outcome.APPLIED;
    }

    private String holdReason(FraudVerdict verdict) {
        String reason = verdict.reasons().isEmpty()
                ? "Fraud score " + verdict.score()
                : String.join("; ", verdict.reasons());
        return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }
}
