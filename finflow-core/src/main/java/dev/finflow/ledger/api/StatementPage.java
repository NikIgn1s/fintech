package dev.finflow.ledger.api;

import java.math.BigDecimal;
import java.util.List;

public record StatementPage(
        Long accountId,
        String currency,
        BigDecimal balance,
        List<StatementItem> items,
        Long nextCursor) {
}
