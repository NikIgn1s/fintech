package dev.finflow.ledger.api;

import java.util.List;
import java.util.UUID;

public interface LedgerOperations {
    void post(LedgerTransactionType type,UUID referenceId,List<Posting> postings);
}
