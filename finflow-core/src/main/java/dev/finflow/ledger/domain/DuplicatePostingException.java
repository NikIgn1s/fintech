package dev.finflow.ledger.domain;

import dev.finflow.common.error.ConflictException;
import java.util.UUID;

public class DuplicatePostingException extends ConflictException {

    public DuplicatePostingException(LedgerTransactionType type, UUID referenceId) {
        super("duplicate-posting", "Duplicate posting",
                "Ledger transaction " + type + " for " + referenceId + " already exists");
    }
}
