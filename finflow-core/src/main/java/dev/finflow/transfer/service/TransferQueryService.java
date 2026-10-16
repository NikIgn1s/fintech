package dev.finflow.transfer.service;

import dev.finflow.account.service.AccountService;
import dev.finflow.common.error.NotFoundException;
import dev.finflow.transfer.api.TransferDirection;
import dev.finflow.transfer.api.TransferMapper;
import dev.finflow.transfer.api.TransferPage;
import dev.finflow.transfer.api.TransferResponse;
import dev.finflow.transfer.domain.Transfer;
import dev.finflow.transfer.domain.TransferStatus;
import dev.finflow.transfer.repository.TransferRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransferQueryService {

    private static final UUID MAX_UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    private final TransferRepository transfers;
    private final AccountService accounts;
    private final TransferMapper mapper;

    @Transactional(readOnly = true)
    public TransferResponse get(UUID userId, UUID transferId) {
        Transfer transfer = transfers.findById(transferId)
                .filter(candidate -> isParticipant(userId, candidate))
                .orElseThrow(() -> new NotFoundException("Transfer " + transferId + " not found"));
        return mapper.toResponse(transfer);
    }

    @Transactional(readOnly = true)
    public TransferPage list(UUID userId, TransferDirection direction, TransferStatus status, UUID after, int limit) {
        boolean incoming = direction == null || direction == TransferDirection.IN;
        boolean outgoing = direction == null || direction == TransferDirection.OUT;
        List<Transfer> page = transfers.findHistory(userId, incoming, outgoing,
                status == null ? null : status.name(), after == null ? MAX_UUID : after, limit + 1);

        boolean hasMore = page.size() > limit;
        List<Transfer> visible = hasMore ? page.subList(0, limit) : page;
        UUID nextCursor = hasMore ? visible.get(visible.size() - 1).getId() : null;
        return new TransferPage(visible.stream().map(mapper::toResponse).toList(), nextCursor);
    }

    private boolean isParticipant(UUID userId, Transfer transfer) {
        return transfer.getInitiatedBy().equals(userId)
                || userId.equals(accounts.getTransferTarget(transfer.getToAccountId()).userId());
    }
}
