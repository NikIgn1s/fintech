package dev.finflow.ledger.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StatementItem(
        Long entryId,
        LedgerTransactionType type,
        UUID referenceId,
        BigDecimal amount,
        Instant createdAt) {
}
