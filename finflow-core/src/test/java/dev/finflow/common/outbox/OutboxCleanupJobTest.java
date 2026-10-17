package dev.finflow.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finflow.support.AbstractPostgresTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class OutboxCleanupJobTest extends AbstractPostgresTest {

    @Autowired
    private OutboxCleanupJob job;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID event(String publishedAtExpression) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into outbox_events (id, aggregate_type, aggregate_id, event_type, topic, payload, "
                + "published_at) values (?, 'transfer', 'c', 'E', 'cleanup-topic', '{}'::jsonb, "
                + publishedAtExpression + ")", id);
        return id;
    }

    private boolean exists(UUID id) {
        Integer count = jdbc.queryForObject("select count(*) from outbox_events where id = ?", Integer.class, id);
        return count == 1;
    }

    @Test
    void removesOnlyEventsPublishedLongAgo() {
        UUID old = event("now() - interval '8 days'");
        UUID recent = event("now() - interval '1 day'");
        UUID unpublished = event("null");

        job.cleanup();

        assertThat(exists(old)).isFalse();
        assertThat(exists(recent)).isTrue();
        assertThat(exists(unpublished)).isTrue();
    }
}
