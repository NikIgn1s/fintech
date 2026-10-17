package dev.finflow.common.messaging;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class ProcessedEventStore {

    private static final String MARK = """
            INSERT INTO processed_events (event_id, consumer) VALUES (?, ?)
            ON CONFLICT DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID eventId, String consumer) {
        return jdbc.update(MARK, eventId, consumer) == 1;
    }
}
