package dev.finflow.importing.batch;

import dev.finflow.common.messaging.EventTypes;
import dev.finflow.common.messaging.Topics;
import dev.finflow.common.outbox.OutboxPublisher;
import dev.finflow.importing.messaging.TransactionsImported;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ImportFinalizer {

    private final ImportProgress progress;
    private final JdbcTemplate jdbc;
    private final OutboxPublisher outbox;

    public void finish(UUID importId, UUID userId) {
        progress.complete(importId);
        var counters = jdbc.queryForMap("select rows_imported, rows_skipped, rows_failed "
                + "from statement_imports where id = ?", importId);
        int imported = ((Number) counters.get("rows_imported")).intValue();
        if (imported == 0) {
            return;
        }
        var range = jdbc.queryForMap("select min(operation_date) as first_day, max(operation_date) as last_day "
                + "from bank_transactions where import_id = ?", importId);
        outbox.publish("import", userId.toString(), EventTypes.TRANSACTIONS_IMPORTED, Topics.TRANSACTIONS_IMPORTED,
                new TransactionsImported(importId, userId, imported,
                        ((Number) counters.get("rows_skipped")).intValue(),
                        ((Number) counters.get("rows_failed")).intValue(),
                        toLocal((Date) range.get("first_day")), toLocal((Date) range.get("last_day"))));
    }

    private LocalDate toLocal(Date date) {
        return date.toLocalDate();
    }
}
