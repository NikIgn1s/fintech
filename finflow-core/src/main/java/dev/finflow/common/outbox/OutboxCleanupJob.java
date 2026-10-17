package dev.finflow.common.outbox;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxCleanupJob {

    private final OutboxRepository outbox;
    private final OutboxProperties properties;
    private final Clock clock;

    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    @SchedulerLock(name = "outboxCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void cleanup() {
        int deleted = outbox.deletePublishedBefore(clock.instant().minus(properties.retention()));
        if (deleted > 0) {
            log.info("Removed {} published outbox events", deleted);
        }
    }
}
