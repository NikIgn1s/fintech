package dev.finflow.transfer.fraud;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "finflow.fraud.fallback.enabled", havingValue = "true", matchIfMissing = true)
public class FraudFallbackJob {

    private final FraudFallbackService fallback;

    @Scheduled(fixedDelayString = "${finflow.fraud.fallback.poll-interval:10000}", initialDelay = 15_000)
    @SchedulerLock(name = "fraudFallback", lockAtMostFor = "PT2M")
    public void run() {
        fallback.resolveStale();
    }
}
