package dev.finflow.budget.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.budget.service.BudgetService;
import dev.finflow.common.messaging.*;
import dev.finflow.common.outbox.EventEnvelope;
import dev.finflow.importing.api.events.TransactionsImported;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="finflow.kafka.listeners.enabled", havingValue="true", matchIfMissing=true)
public class BudgetImportListener {
    private final ObjectMapper mapper;
    private final ProcessedEventStore events;
    private final BudgetService budgets;

    @Transactional
    @KafkaListener(topics={Topics.TRANSACTIONS_IMPORTED, "transactions.categorized"}, groupId="finflow-budgets")
    public void onMessage(String value) {
        try {
            var event = mapper.readValue(value, EventEnvelope.class);
            if (event == null || event.eventId() == null || event.version() != 1
                    || !(EventTypes.TRANSACTIONS_IMPORTED.equals(event.eventType())
                        || "TransactionsCategorized".equals(event.eventType())) || event.payload() == null || event.payload().isNull())
                throw new InvalidEventException("Invalid imported transactions event");
            var payload = mapper.treeToValue(event.payload(), TransactionsImported.class);
            if (payload.userId() == null) throw new InvalidEventException("Missing userId");
            if (events.markProcessed(event.eventId(), "budget-import")) budgets.recalculate(payload.userId());
        } catch (JsonProcessingException error) {
            throw new InvalidEventException("Malformed imported transactions event", error);
        }
    }
}
