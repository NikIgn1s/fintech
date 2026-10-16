package dev.finflow.ledger.domain;

import java.time.Instant;
import java.util.List;

public record ReconciliationReport(
        Long id,
        Instant startedAt,
        Instant finishedAt,
        int mismatchCount,
        List<BalanceMismatch> mismatches) {
}
