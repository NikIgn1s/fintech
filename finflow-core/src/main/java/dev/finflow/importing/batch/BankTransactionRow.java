package dev.finflow.importing.batch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BankTransactionRow(
        UUID userId,
        UUID importId,
        LocalDate operationDate,
        BigDecimal amount,
        String currency,
        String rawDescription,
        String merchant,
        String mcc,
        String dedupHash) {
}
