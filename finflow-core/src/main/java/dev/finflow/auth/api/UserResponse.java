package dev.finflow.auth.api;

import dev.finflow.auth.domain.Role;
import java.util.UUID;

public record UserResponse(UUID id, String email, String fullName, Role role) {
}
