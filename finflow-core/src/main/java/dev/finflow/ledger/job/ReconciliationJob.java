package dev.finflow.ledger.job;

import dev.finflow.ledger.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "finflow.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    private final ReconciliationService reconciliation;

    @Scheduled(cron = "${finflow.reconciliation.cron:0 0 3 * * *}", zone = "UTC")
    public void reconcile() {
        var report = reconciliation.run();
        log.info("Ledger reconciliation finished: run={}, mismatches={}", report.id(), report.mismatchCount());
    }
}
