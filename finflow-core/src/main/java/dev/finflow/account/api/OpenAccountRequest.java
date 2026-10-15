package dev.finflow.account.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record OpenAccountRequest(
        @NotNull OpenableAccountType type,
        @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO currency code") String currency) {
}
