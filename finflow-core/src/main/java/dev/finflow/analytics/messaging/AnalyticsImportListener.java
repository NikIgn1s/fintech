package dev.finflow.analytics.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.analytics.service.AnalyticsRefreshService;
import dev.finflow.common.messaging.InvalidEventException;
import dev.finflow.common.outbox.EventEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="finflow.kafka.listeners.enabled",havingValue="true",matchIfMissing=true)
public class AnalyticsImportListener {
    private final ObjectMapper mapper;
    private final AnalyticsRefreshService refresh;

    @KafkaListener(topics={"transactions.imported","transactions.categorized"},groupId="finflow-analytics")
    public void onMessage(String message) {
        try {
            EventEnvelope event=mapper.readValue(message,EventEnvelope.class);
            if (event==null || event.eventId()==null || event.version()!=1
                    || !("TransactionsImported".equals(event.eventType())
                         || "TransactionsCategorized".equals(event.eventType()))
                    || event.payload()==null || !event.payload().hasNonNull("userId"))
                throw new InvalidEventException("Invalid analytics event");
            refresh.refresh();
        } catch (JsonProcessingException error) {
            throw new InvalidEventException("Malformed analytics event",error);
        }
    }
}
