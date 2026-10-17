package dev.finflow.transfer.messaging;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferCheckRequested(
        UUID transferId,
        UUID fromUserId,
        UUID toUserId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        long fromAccountAgeDays,
        boolean isFirstTransferToRecipient,
        long transfersLast1h,
        long transfersLast24h,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amountLast24h,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal avgTransferAmount30d,
        int hourOfDay,
        long recipientAccountAgeDays,
        long recipientIncomingCount24h,
        Instant createdAt) {
}
