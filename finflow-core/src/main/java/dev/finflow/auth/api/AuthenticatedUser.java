package dev.finflow.auth.api;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, Role role) {
}
