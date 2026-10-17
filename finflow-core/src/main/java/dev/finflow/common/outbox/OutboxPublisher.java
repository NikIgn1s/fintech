package dev.finflow.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.common.id.UuidV7;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String aggregateType, String aggregateId, String eventType, String topic, Object payload) {
        UUID eventId = UuidV7.generate();
        Instant now = clock.instant();
        EventEnvelope envelope = new EventEnvelope(eventId, eventType, EventEnvelope.CURRENT_VERSION, now,
                objectMapper.valueToTree(payload));
        outbox.save(OutboxEvent.create(eventId, aggregateType, aggregateId, eventType, topic, serialize(envelope), now));
        return eventId;
    }

    private String serialize(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + envelope.eventType(), e);
        }
    }
}
