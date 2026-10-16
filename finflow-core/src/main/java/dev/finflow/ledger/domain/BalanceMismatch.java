package dev.finflow.ledger.domain;

import java.math.BigDecimal;

public record BalanceMismatch(Long accountId, BigDecimal balance, BigDecimal ledgerSum) {
}
