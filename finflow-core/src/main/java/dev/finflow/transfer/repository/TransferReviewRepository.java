package dev.finflow.transfer.repository;

import dev.finflow.transfer.domain.TransferReview;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferReviewRepository extends JpaRepository<TransferReview, Long> {

    Optional<TransferReview> findByTransferId(UUID transferId);
}
