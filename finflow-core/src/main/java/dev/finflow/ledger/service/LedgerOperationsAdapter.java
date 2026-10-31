package dev.finflow.ledger.service;

import dev.finflow.ledger.LedgerService;
import dev.finflow.ledger.api.LedgerOperations;
import dev.finflow.ledger.api.LedgerTransactionType;
import dev.finflow.ledger.api.Posting;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LedgerOperationsAdapter implements LedgerOperations {
    private final LedgerService ledger;

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void post(LedgerTransactionType type,UUID referenceId,List<Posting> postings) {
        ledger.post(type,referenceId,postings);
    }
}
