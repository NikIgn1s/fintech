package dev.finflow.common.outbox;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "finflow.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private final OutboxBatchPublisher batchPublisher;

    @Scheduled(fixedDelayString = "${finflow.outbox.poll-interval:500}",
            initialDelayString = "${finflow.outbox.initial-delay:1000}")
    @SchedulerLock(name = "outboxRelay", lockAtMostFor = "PT30S")
    public void relay() {
        batchPublisher.publishBatch();
    }
}
