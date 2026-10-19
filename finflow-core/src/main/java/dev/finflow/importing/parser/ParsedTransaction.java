package dev.finflow.importing.parser;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ParsedTransaction(
        LocalDate operationDate,
        BigDecimal amount,
        String currency,
        String description,
        String mcc) {
}
