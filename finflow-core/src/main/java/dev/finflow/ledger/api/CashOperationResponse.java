package dev.finflow.ledger.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CashOperationResponse(
        UUID id,
        LedgerTransactionType type,
        Long accountId,
        BigDecimal amount,
        String currency,
        BigDecimal balance,
        Instant createdAt) {
}
