package dev.finflow.common.idempotency;

import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyCleanupJob {

    static final Duration RETENTION = Duration.ofHours(24);

    private final IdempotencyStore store;
    private final Clock clock;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    public void cleanup() {
        int deleted = store.deleteOlderThan(clock.instant().minus(RETENTION));
        if (deleted > 0) {
            log.info("Removed {} expired idempotency keys", deleted);
        }
    }
}
