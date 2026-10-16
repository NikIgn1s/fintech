package dev.finflow.transfer.api;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateTransferRequest(
        @NotNull Long fromAccountId,
        @NotNull Long toAccountId,
        @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount,
        @Size(max = 255) String description) {
}
