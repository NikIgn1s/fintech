package dev.finflow.ledger.api;

public enum LedgerTransactionType {
    TRANSFER_RESERVE,
    TRANSFER_SETTLE,
    TRANSFER_REFUND,
    TOP_UP,
    WITHDRAWAL
}
