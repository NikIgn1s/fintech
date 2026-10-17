package dev.finflow.transfer.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.common.messaging.InvalidEventException;
import dev.finflow.common.messaging.Topics;
import dev.finflow.common.outbox.EventEnvelope;
import dev.finflow.transfer.service.FraudResultHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "finflow.kafka.listeners.enabled", havingValue = "true", matchIfMissing = true)
public class FraudResultListener {

    private final ObjectMapper objectMapper;
    private final FraudResultHandler handler;

    @KafkaListener(topics = Topics.TRANSFERS_CHECK_RESULT, groupId = "${spring.kafka.consumer.group-id:finflow-core}")
    public void onMessage(String value) {
        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(value, EventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("Malformed fraud result event", e);
        }
        handler.handle(envelope);
    }
}
