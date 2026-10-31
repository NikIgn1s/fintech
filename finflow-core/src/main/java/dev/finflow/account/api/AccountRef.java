package dev.finflow.account.api;

import java.time.Instant;
import java.util.UUID;

public record AccountRef(Long id, UUID userId, String currency, AccountStatus status, Instant createdAt) {
}
