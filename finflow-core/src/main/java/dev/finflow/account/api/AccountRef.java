package dev.finflow.account.api;

import dev.finflow.account.domain.AccountStatus;
import java.util.UUID;

public record AccountRef(Long id, UUID userId, String currency, AccountStatus status) {
}
