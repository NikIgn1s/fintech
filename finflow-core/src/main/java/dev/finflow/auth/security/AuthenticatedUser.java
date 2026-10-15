package dev.finflow.auth.security;

import dev.finflow.auth.domain.Role;
import java.util.UUID;

public record AuthenticatedUser(UUID userId, Role role) {
}
