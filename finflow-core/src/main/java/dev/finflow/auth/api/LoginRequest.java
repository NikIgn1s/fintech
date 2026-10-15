package dev.finflow.auth.api;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password) {
}
