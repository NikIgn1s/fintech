package dev.finflow.ledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.ledger.domain.BalanceMismatch;
import dev.finflow.ledger.domain.ReconciliationReport;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class ReconciliationService {

    private static final String FIND_MISMATCHES = """
            SELECT b.account_id, b.balance, COALESCE(SUM(e.amount), 0) AS ledger_sum
            FROM account_balances b
            LEFT JOIN ledger_entries e ON e.account_id = b.account_id
            GROUP BY b.account_id, b.balance
            HAVING b.balance <> COALESCE(SUM(e.amount), 0)
            ORDER BY b.account_id
            """;

    private static final String INSERT_RUN = """
            INSERT INTO reconciliation_runs (started_at, finished_at, mismatch_count, details)
            VALUES (?, ?, ?, ?::jsonb)
            RETURNING id
            """;

    private static final String LATEST_RUN = """
            SELECT id, started_at, finished_at, mismatch_count, details
            FROM reconciliation_runs
            ORDER BY id DESC
            LIMIT 1
            """;

    private static final TypeReference<List<BalanceMismatch>> MISMATCH_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AtomicInteger mismatchGauge;

    public ReconciliationService(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.mismatchGauge = registry.gauge("ledger_reconciliation_mismatch", new AtomicInteger());
    }

    @Transactional
    public ReconciliationReport run() {
        Instant startedAt = clock.instant();
        List<BalanceMismatch> mismatches = jdbc.query(FIND_MISMATCHES, (rs, row) -> new BalanceMismatch(
                rs.getLong("account_id"), rs.getBigDecimal("balance"), rs.getBigDecimal("ledger_sum")));
        Instant finishedAt = clock.instant();

        Long id = jdbc.queryForObject(INSERT_RUN, Long.class, Timestamp.from(startedAt), Timestamp.from(finishedAt),
                mismatches.size(), toJson(mismatches));
        mismatchGauge.set(mismatches.size());
        if (!mismatches.isEmpty()) {
            log.error("Ledger reconciliation found {} mismatching accounts: {}", mismatches.size(), mismatches);
        }
        return new ReconciliationReport(id, startedAt, finishedAt, mismatches.size(), mismatches);
    }

    @Transactional(readOnly = true)
    public Optional<ReconciliationReport> latest() {
        return jdbc.query(LATEST_RUN, (rs, row) -> new ReconciliationReport(
                rs.getLong("id"),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                rs.getObject("finished_at", OffsetDateTime.class).toInstant(),
                rs.getInt("mismatch_count"),
                fromJson(rs.getString("details")))).stream().findFirst();
    }

    private String toJson(List<BalanceMismatch> mismatches) {
        try {
            return objectMapper.writeValueAsString(mismatches);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize reconciliation details", e);
        }
    }

    private List<BalanceMismatch> fromJson(String json) {
        try {
            return objectMapper.readValue(json, MISMATCH_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read reconciliation details", e);
        }
    }
}
