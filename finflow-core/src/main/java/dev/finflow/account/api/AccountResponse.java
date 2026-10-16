package dev.finflow.account.api;

import dev.finflow.account.domain.AccountStatus;
import dev.finflow.account.domain.AccountType;
import java.math.BigDecimal;
import java.time.Instant;

public record AccountResponse(
        Long id,
        AccountType type,
        String currency,
        AccountStatus status,
        BigDecimal balance,
        Instant createdAt) {
}
