package dev.finflow.transfer.repository;

import dev.finflow.transfer.domain.Transfer;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {
}
