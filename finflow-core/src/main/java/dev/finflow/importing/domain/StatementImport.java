package dev.finflow.importing.domain;

import dev.finflow.importing.parser.BankFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "statement_imports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StatementImport {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "bank_format", nullable = false, updatable = false)
    private BankFormat bankFormat;

    @Column(name = "file_key", nullable = false, updatable = false)
    private String fileKey;

    @Column(name = "original_name", updatable = false)
    private String originalName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ImportStatus status;

    @Column(name = "rows_total", updatable = false)
    private int rowsTotal;

    @Column(name = "rows_imported", updatable = false)
    private int rowsImported;

    @Column(name = "rows_skipped", updatable = false)
    private int rowsSkipped;

    @Column(name = "rows_failed", updatable = false)
    private int rowsFailed;

    @Column(name = "job_execution_id", updatable = false)
    private Long jobExecutionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finished_at", updatable = false)
    private Instant finishedAt;

    public static StatementImport uploaded(UUID id, UUID userId, BankFormat format, String fileKey,
            String originalName, Instant now) {
        StatementImport statementImport = new StatementImport();
        statementImport.id = id;
        statementImport.userId = userId;
        statementImport.bankFormat = format;
        statementImport.fileKey = fileKey;
        statementImport.originalName = originalName;
        statementImport.status = ImportStatus.UPLOADED;
        statementImport.createdAt = now;
        return statementImport;
    }
}
