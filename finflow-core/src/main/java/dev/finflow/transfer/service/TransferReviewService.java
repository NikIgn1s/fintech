package dev.finflow.transfer.service;

import dev.finflow.common.error.NotFoundException;
import dev.finflow.transfer.api.AdminTransferMapper;
import dev.finflow.transfer.api.AdminTransferPage;
import dev.finflow.transfer.api.AdminTransferResponse;
import dev.finflow.transfer.domain.IllegalTransferStateException;
import dev.finflow.transfer.domain.ReviewDecision;
import dev.finflow.transfer.domain.Transfer;
import dev.finflow.transfer.domain.TransferReview;
import dev.finflow.transfer.domain.TransferStatus;
import dev.finflow.transfer.repository.TransferRepository;
import dev.finflow.transfer.repository.TransferReviewRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransferReviewService {

    private static final String DECIDED_BY = "ANALYST";

    private final TransferRepository transfers;
    private final TransferReviewRepository reviews;
    private final TransferSettlement settlement;
    private final TransferEventPublisher events;
    private final AdminTransferMapper mapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AdminTransferPage list(TransferStatus status, int page, int size) {
        Sort sort = Sort.by(Sort.Order.desc("fraudScore").nullsLast(), Sort.Order.asc("createdAt"));
        Page<Transfer> result = transfers.findByStatus(status, PageRequest.of(page, size, sort));
        return new AdminTransferPage(result.getContent().stream().map(mapper::toResponse).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public AdminTransferResponse approve(UUID reviewerId, UUID transferId) {
        Transfer transfer = heldTransfer(transferId, TransferStatus.COMPLETED);
        Instant now = clock.instant();
        settlement.settle(transfer);
        transfer.complete(null, now);
        return finish(transfer, TransferReview.of(transferId, reviewerId, ReviewDecision.APPROVE, null,
                transfer.getFraudScore(), now), now);
    }

    @Transactional
    public AdminTransferResponse reject(UUID reviewerId, UUID transferId, String reason) {
        Transfer transfer = heldTransfer(transferId, TransferStatus.REJECTED);
        Instant now = clock.instant();
        settlement.refund(transfer);
        transfer.reject(null, now);
        return finish(transfer, TransferReview.of(transferId, reviewerId, ReviewDecision.REJECT, reason,
                transfer.getFraudScore(), now), now);
    }

    private AdminTransferResponse finish(Transfer transfer, TransferReview review, Instant now) {
        reviews.save(review);
        events.statusChanged(transfer, DECIDED_BY, now);
        return mapper.toResponse(transfer);
    }

    private Transfer heldTransfer(UUID transferId, TransferStatus target) {
        Transfer transfer = transfers.findById(transferId)
                .orElseThrow(() -> new NotFoundException("Transfer " + transferId + " not found"));
        if (transfer.getStatus() != TransferStatus.ON_HOLD) {
            throw new IllegalTransferStateException(transferId, transfer.getStatus(), target);
        }
        return transfer;
    }
}
