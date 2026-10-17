package dev.finflow.transfer.messaging;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.finflow.transfer.domain.FraudSource;
import dev.finflow.transfer.domain.TransferStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferStatusChanged(
        UUID transferId,
        TransferStatus status,
        UUID fromUserId,
        UUID toUserId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        FraudSource decidedBy,
        Instant occurredAt) {
}
