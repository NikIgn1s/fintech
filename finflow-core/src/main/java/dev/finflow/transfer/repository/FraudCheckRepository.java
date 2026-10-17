package dev.finflow.transfer.repository;

import dev.finflow.transfer.domain.FraudCheck;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudCheckRepository extends JpaRepository<FraudCheck, Long> {

    List<FraudCheck> findByTransferIdOrderById(UUID transferId);
}
