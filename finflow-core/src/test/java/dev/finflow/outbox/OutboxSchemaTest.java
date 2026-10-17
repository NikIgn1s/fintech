package dev.finflow.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finflow.support.AbstractLedgerSchemaTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class OutboxSchemaTest extends AbstractLedgerSchemaTest {

    private UUID insertEvent(String aggregateId, String payload) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into outbox_events (id, aggregate_type, aggregate_id, event_type, topic, payload) "
                + "values (?, 'transfer', ?, 'TransferCheckRequested', 'transfers.check', ?::jsonb)",
                id, aggregateId, payload);
        return id;
    }

    @Test
    void newEventIsUnpublishedByDefault() {
        UUID id = insertEvent("t-1", "{\"transferId\":\"t-1\"}");

        Object publishedAt = jdbc.queryForObject("select published_at from outbox_events where id = ?",
                Object.class, id);
        String transferId = jdbc.queryForObject("select payload ->> 'transferId' from outbox_events where id = ?",
                String.class, id);

        assertThat(publishedAt).isNull();
        assertThat(transferId).isEqualTo("t-1");
    }

    @Test
    void unpublishedEventsComeOutOldestFirst() {
        String marker = "order-" + UUID.randomUUID();
        UUID first = insertEvent(marker, "{}");
        UUID second = insertEvent(marker, "{}");
        jdbc.update("update outbox_events set created_at = now() - interval '1 minute' where id = ?", second);

        List<UUID> ordered = jdbc.queryForList("select id from outbox_events where aggregate_id = ? "
                + "and published_at is null order by created_at", UUID.class, marker);

        assertThat(ordered).containsExactly(second, first);
    }

    @Test
    void publishedEventsLeaveTheUnpublishedSet() {
        String marker = "pub-" + UUID.randomUUID();
        UUID id = insertEvent(marker, "{}");

        jdbc.update("update outbox_events set published_at = now() where id = ?", id);

        Integer pending = jdbc.queryForObject(
                "select count(*) from outbox_events where aggregate_id = ? and published_at is null",
                Integer.class, marker);
        assertThat(pending).isZero();
    }

    @Test
    void rejectsPayloadThatIsNotJson() {
        assertThatThrownBy(() -> insertEvent("bad", "not json")).isInstanceOf(Exception.class);
    }

    @Test
    void requiresTopicAndPayload() {
        assertThatThrownBy(() -> jdbc.update("insert into outbox_events (id, aggregate_type, aggregate_id, "
                + "event_type, topic, payload) values (?, 'transfer', 'x', 'E', null, '{}'::jsonb)", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameEventCanBeProcessedOncePerConsumer() {
        UUID eventId = UUID.randomUUID();
        String insert = "insert into processed_events (event_id, consumer) values (?, ?)";

        jdbc.update(insert, eventId, "fraud-result");
        jdbc.update(insert, eventId, "budget");

        assertThatThrownBy(() -> jdbc.update(insert, eventId, "fraud-result"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
