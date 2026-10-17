package dev.finflow.common.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    Optional<OutboxEvent> findFirstByAggregateIdAndEventTypeOrderByCreatedAtDesc(String aggregateId,
            String eventType);

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
            ORDER BY created_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("limit") int limit);

    long countByPublishedAtIsNull();

    @Transactional
    @Modifying
    @Query("delete from OutboxEvent e where e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
