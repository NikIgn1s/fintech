package dev.finflow.transfer.service;

import java.math.BigDecimal;

public record CreateTransferCommand(Long fromAccountId, Long toAccountId, BigDecimal amount, String description) {
}
