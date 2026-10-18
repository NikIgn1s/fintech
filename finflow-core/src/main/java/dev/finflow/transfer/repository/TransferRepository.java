package dev.finflow.transfer.repository;

import dev.finflow.transfer.domain.Transfer;
import dev.finflow.transfer.domain.TransferStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Page<Transfer> findByStatus(TransferStatus status, Pageable pageable);

    List<Transfer> findByStatusAndCreatedAtBeforeOrderByCreatedAt(TransferStatus status, Instant before,
            Pageable pageable);

    @Query("""
            select coalesce(sum(t.amount), 0) from Transfer t
            where t.initiatedBy = :userId and t.currency = :currency
              and t.createdAt >= :since and t.status in :statuses
            """)
    BigDecimal sumInitiatedSince(@Param("userId") UUID userId, @Param("currency") String currency,
            @Param("since") Instant since, @Param("statuses") Collection<TransferStatus> statuses);

    @Query("""
            select count(t) from Transfer t
            where t.initiatedBy = :userId and t.currency = :currency
              and t.createdAt >= :since and t.status in :statuses
            """)
    long countInitiatedSince(@Param("userId") UUID userId, @Param("currency") String currency,
            @Param("since") Instant since, @Param("statuses") Collection<TransferStatus> statuses);

    @Query("""
            select count(t) from Transfer t
            where t.toAccountId = :accountId and t.createdAt >= :since and t.status in :statuses
            """)
    long countIncomingSince(@Param("accountId") Long accountId, @Param("since") Instant since,
            @Param("statuses") Collection<TransferStatus> statuses);

    boolean existsByInitiatedByAndToAccountIdAndStatus(UUID initiatedBy, Long toAccountId, TransferStatus status);

    @Query(value = """
            SELECT t.* FROM transfers t
            WHERE ((:outgoing AND t.initiated_by = :userId)
                   OR (:incoming AND t.to_account_id IN (SELECT a.id FROM accounts a WHERE a.user_id = :userId)))
              AND (CAST(:status AS varchar) IS NULL OR t.status = CAST(:status AS varchar))
              AND t.id < :before
            ORDER BY t.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Transfer> findHistory(@Param("userId") UUID userId, @Param("incoming") boolean incoming,
            @Param("outgoing") boolean outgoing, @Param("status") String status, @Param("before") UUID before,
            @Param("limit") int limit);
}
