package dev.finflow.importing.batch;

import dev.finflow.importing.parser.BankFormat;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ImportProgress {

    public record ImportInfo(UUID userId, BankFormat format, String fileKey) {
    }

    private static final int MAX_REASON_LENGTH = 255;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ImportInfo find(UUID importId) {
        return jdbc.query("select user_id, bank_format, file_key from statement_imports where id = ?",
                (rs, row) -> new ImportInfo(rs.getObject("user_id", UUID.class),
                        BankFormat.valueOf(rs.getString("bank_format")), rs.getString("file_key")), importId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Import " + importId + " does not exist"));
    }

    public void markProcessing(UUID importId, long jobExecutionId) {
        jdbc.update("update statement_imports set status = 'PROCESSING', job_execution_id = ? where id = ?",
                jobExecutionId, importId);
    }

    public void addInserted(UUID importId, int imported, int duplicates) {
        jdbc.update("update statement_imports set rows_imported = rows_imported + ?, "
                + "rows_skipped = rows_skipped + ? where id = ?", imported, duplicates, importId);
    }

    public void addFailure(UUID importId, int lineNumber, String reason, String rawLine) {
        recordError(importId, lineNumber, reason, rawLine);
        jdbc.update("update statement_imports set rows_failed = rows_failed + 1 where id = ?", importId);
    }

    public void complete(UUID importId) {
        finish(importId, "DONE");
    }

    public void fail(UUID importId, String reason) {
        recordError(importId, 0, reason, null);
        finish(importId, "FAILED");
    }

    private void finish(UUID importId, String status) {
        jdbc.update("update statement_imports set status = ?, finished_at = ?, "
                + "rows_total = rows_imported + rows_skipped + rows_failed where id = ?",
                status, Timestamp.from(clock.instant()), importId);
    }

    private void recordError(UUID importId, int lineNumber, String reason, String rawLine) {
        String shortReason = reason == null ? "Unknown error"
                : reason.substring(0, Math.min(reason.length(), MAX_REASON_LENGTH));
        jdbc.update("insert into import_errors (import_id, line_number, reason, raw_line) values (?, ?, ?, ?)",
                importId, lineNumber, shortReason, rawLine);
    }
}
