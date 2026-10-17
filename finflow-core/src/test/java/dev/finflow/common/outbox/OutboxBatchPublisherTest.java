package dev.finflow.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OutboxBatchPublisherTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private final OutboxRepository outbox = mock(OutboxRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private final OutboxBatchPublisher publisher = new OutboxBatchPublisher(outbox, kafka,
            new OutboxProperties(100, Duration.ofSeconds(1), Duration.ofDays(7)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private OutboxEvent event(String aggregateId) {
        return OutboxEvent.create(UUID.randomUUID(), "transfer", aggregateId, "TransferCheckRequested",
                "transfers.check", "{}", NOW.minusSeconds(10));
    }

    @Test
    void marksEveryDeliveredEventAsPublished() {
        List<OutboxEvent> batch = List.of(event("a"), event("b"), event("c"));
        when(outbox.lockNextBatch(100)).thenReturn(batch);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(
                (SendResult<String, String>) null));

        int published = publisher.publishBatch();

        assertThat(published).isEqualTo(3);
        assertThat(batch).allSatisfy(event -> assertThat(event.getPublishedAt()).isEqualTo(NOW));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stopsAtFirstFailureKeepsEarlierSuccessesAndLeavesTheRestPending() {
        OutboxEvent first = event("a");
        OutboxEvent second = event("b");
        OutboxEvent third = event("c");
        when(outbox.lockNextBatch(100)).thenReturn(List.of(first, second, third));
        CompletableFuture<SendResult<String, String>> failure = new CompletableFuture<>();
        failure.completeExceptionally(new IllegalStateException("broker down"));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null))
                .thenReturn(failure);

        int published = publisher.publishBatch();

        assertThat(published).isEqualTo(1);
        assertThat(first.isPublished()).isTrue();
        assertThat(second.isPublished()).isFalse();
        assertThat(third.isPublished()).isFalse();
        verify(kafka, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    void doesNothingWhenThereIsNothingToPublish() {
        when(outbox.lockNextBatch(100)).thenReturn(List.of());

        assertThat(publisher.publishBatch()).isZero();
        verify(kafka, times(0)).send(any(ProducerRecord.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsKeyHeadersAndPayload() {
        OutboxEvent event = event("transfer-42");
        when(outbox.lockNextBatch(100)).thenReturn(List.of(event));
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            assertThat(record.topic()).isEqualTo("transfers.check");
            assertThat(record.key()).isEqualTo("transfer-42");
            assertThat(record.value()).isEqualTo("{}");
            assertThat(new String(record.headers().lastHeader("eventId").value())).isEqualTo(event.getId().toString());
            assertThat(new String(record.headers().lastHeader("eventType").value()))
                    .isEqualTo("TransferCheckRequested");
            return CompletableFuture.completedFuture((SendResult<String, String>) null);
        });

        assertThat(publisher.publishBatch()).isEqualTo(1);
    }
}
