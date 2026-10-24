package dev.finflow.importing.api;

import dev.finflow.importing.domain.ImportStatus;
import dev.finflow.importing.domain.StatementImport;
import dev.finflow.importing.parser.BankFormat;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ImportResponse(
        UUID id,
        BankFormat bankFormat,
        ImportStatus status,
        String originalName,
        int rowsTotal,
        int rowsImported,
        int rowsSkipped,
        int rowsFailed,
        Instant createdAt,
        Instant finishedAt,
        List<ImportErrorItem> errors) {

    public static ImportResponse of(StatementImport source, List<ImportErrorItem> errors) {
        return new ImportResponse(source.getId(), source.getBankFormat(), source.getStatus(),
                source.getOriginalName(), source.getRowsTotal(), source.getRowsImported(), source.getRowsSkipped(),
                source.getRowsFailed(), source.getCreatedAt(), source.getFinishedAt(), errors);
    }
}
