package dev.finflow.common.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxBatchPublisher {

    public static final String EVENT_ID_HEADER = "eventId";
    public static final String EVENT_TYPE_HEADER = "eventType";

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties properties;
    private final Clock clock;

    @Transactional
    public int publishBatch() {
        List<OutboxEvent> batch = outbox.lockNextBatch(properties.batchSize());
        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                send(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                log.warn("Could not publish outbox event {} to {}, will retry", event.getId(), event.getTopic(), e);
                break;
            }
            event.markPublished(clock.instant());
            published++;
        }
        return published;
    }

    private void send(OutboxEvent event) throws InterruptedException, ExecutionException, TimeoutException {
        ProducerRecord<String, String> record = new ProducerRecord<>(event.getTopic(), event.getAggregateId(),
                event.getPayload());
        record.headers().add(new RecordHeader(EVENT_ID_HEADER,
                event.getId().toString().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(EVENT_TYPE_HEADER,
                event.getEventType().getBytes(StandardCharsets.UTF_8)));
        kafka.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }
}
