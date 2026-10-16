package dev.finflow.ledger.domain;

public enum LedgerTransactionType {
    TRANSFER_RESERVE,
    TRANSFER_SETTLE,
    TRANSFER_REFUND,
    TOP_UP,
    WITHDRAWAL
}
