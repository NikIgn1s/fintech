package dev.finflow.analytics.service;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="finflow.analytics.refresh-enabled",havingValue="true",matchIfMissing=true)
public class AnalyticsRefreshJob {
    private final AnalyticsRefreshService service;

    @Scheduled(fixedDelayString="${finflow.analytics.refresh-interval:900000}",
            initialDelayString="${finflow.analytics.refresh-interval:900000}")
    @SchedulerLock(name="analyticsRefresh",lockAtMostFor="15m")
    public void refresh() {
        service.refresh();
    }
}
