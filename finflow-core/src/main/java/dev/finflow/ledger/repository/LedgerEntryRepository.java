package dev.finflow.ledger.repository;

import dev.finflow.ledger.domain.LedgerEntry;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    @Query("""
            select e from LedgerEntry e join fetch e.transaction
            where e.accountId = :accountId
              and e.id < :before
              and e.createdAt >= :from
              and e.createdAt < :to
            order by e.id desc
            """)
    List<LedgerEntry> findPage(@Param("accountId") Long accountId, @Param("before") Long before,
            @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);
}
