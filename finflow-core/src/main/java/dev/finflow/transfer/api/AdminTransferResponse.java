package dev.finflow.transfer.api;

import dev.finflow.transfer.domain.TransferStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AdminTransferResponse(
        UUID id,
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount,
        String currency,
        TransferStatus status,
        String description,
        BigDecimal fraudScore,
        String holdReason,
        UUID initiatedBy,
        Instant createdAt,
        Instant updatedAt) {
}
