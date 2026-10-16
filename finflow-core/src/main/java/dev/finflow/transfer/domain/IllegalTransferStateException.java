package dev.finflow.transfer.domain;

import dev.finflow.common.error.ConflictException;
import java.util.UUID;

public class IllegalTransferStateException extends ConflictException {

    public IllegalTransferStateException(UUID transferId, TransferStatus from, TransferStatus to) {
        super("illegal-transfer-state", "Illegal transfer state",
                "Transfer " + transferId + " cannot move from " + from + " to " + to);
    }
}
