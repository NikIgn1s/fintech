package dev.finflow.importing.messaging;

import java.time.LocalDate;
import java.util.UUID;

public record TransactionsImported(
        UUID importId,
        UUID userId,
        int importedRows,
        int skippedRows,
        int failedRows,
        LocalDate earliestDate,
        LocalDate latestDate) {
}
