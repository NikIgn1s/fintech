package dev.finflow.account.api;

import dev.finflow.account.domain.AccountStatus;
import dev.finflow.account.domain.AccountType;
import java.time.Instant;

public record AccountResponse(Long id, AccountType type, String currency, AccountStatus status, Instant createdAt) {
}
