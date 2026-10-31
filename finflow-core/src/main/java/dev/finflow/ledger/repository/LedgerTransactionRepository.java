package dev.finflow.ledger.repository;

import dev.finflow.ledger.domain.LedgerTransaction;
import dev.finflow.ledger.api.LedgerTransactionType;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {

    boolean existsByTypeAndReferenceId(LedgerTransactionType type, UUID referenceId);
}
