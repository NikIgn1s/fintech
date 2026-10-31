package dev.finflow.ledger.domain;

import dev.finflow.ledger.api.LedgerTransactionType;


import java.util.UUID;

public class UnbalancedPostingException extends RuntimeException {

    public UnbalancedPostingException(LedgerTransactionType type, UUID referenceId, String reason) {
        super("Posting " + type + " for " + referenceId + " is invalid: " + reason);
    }
}
