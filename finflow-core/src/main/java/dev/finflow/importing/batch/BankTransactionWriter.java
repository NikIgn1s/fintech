package dev.finflow.importing.batch;

import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BankTransactionWriter implements ItemWriter<BankTransactionRow> {

    private static final String INSERT = """
            INSERT INTO bank_transactions (user_id, import_id, operation_date, amount, currency, raw_description,
                                           merchant, mcc, category_source, dedup_hash)
            VALUES (:userId, :importId, :operationDate, :amount, :currency, :rawDescription,
                    :merchant, :mcc, 'NONE', :dedupHash)
            ON CONFLICT (user_id, dedup_hash) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ImportProgress progress;

    @Override
    public void write(Chunk<? extends BankTransactionRow> chunk) {
        if (chunk.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = chunk.getItems().stream().map(this::parameters)
                .toArray(SqlParameterSource[]::new);
        int[] results = jdbc.batchUpdate(INSERT, batch);
        int inserted = (int) Arrays.stream(results).filter(result -> result != 0).count();
        progress.addInserted(chunk.getItems().get(0).importId(), inserted, results.length - inserted);
    }

    private SqlParameterSource parameters(BankTransactionRow row) {
        return new MapSqlParameterSource()
                .addValue("userId", row.userId())
                .addValue("importId", row.importId())
                .addValue("operationDate", row.operationDate())
                .addValue("amount", row.amount())
                .addValue("currency", row.currency())
                .addValue("rawDescription", row.rawDescription())
                .addValue("merchant", row.merchant())
                .addValue("mcc", row.mcc())
                .addValue("dedupHash", row.dedupHash());
    }
}
