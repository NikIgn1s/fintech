package dev.finflow.common.messaging;

public final class EventTypes {

    public static final String TRANSFER_CHECK_REQUESTED = "TransferCheckRequested";
    public static final String FRAUD_CHECK_COMPLETED = "FraudCheckCompleted";
    public static final String TRANSFER_STATUS_CHANGED = "TransferStatusChanged";
    public static final String TRANSACTIONS_IMPORTED = "TransactionsImported";

    private EventTypes() {
    }
}
