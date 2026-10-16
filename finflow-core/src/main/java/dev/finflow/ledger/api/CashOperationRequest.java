package dev.finflow.ledger.api;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record CashOperationRequest(
        @NotNull Long accountId,
        @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount) {
}
