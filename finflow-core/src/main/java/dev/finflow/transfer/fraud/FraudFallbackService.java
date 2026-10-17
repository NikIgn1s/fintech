package dev.finflow.transfer.fraud;

import dev.finflow.transfer.domain.Transfer;
import dev.finflow.transfer.domain.TransferStatus;
import dev.finflow.transfer.repository.TransferRepository;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudFallbackService {

    private final TransferRepository transfers;
    private final FraudFallbackWorker worker;
    private final FraudFallbackProperties properties;
    private final Clock clock;

    public int resolveStale() {
        List<Transfer> stale = transfers.findByStatusAndCreatedAtBeforeOrderByCreatedAt(
                TransferStatus.PENDING_CHECK, clock.instant().minus(properties.timeout()),
                PageRequest.of(0, properties.batchSize()));
        int resolved = 0;
        for (Transfer transfer : stale) {
            try {
                if (worker.resolve(transfer.getId())) {
                    resolved++;
                }
            } catch (RuntimeException e) {
                log.warn("Fallback decision for transfer {} failed, will retry", transfer.getId(), e);
            }
        }
        if (resolved > 0) {
            log.info("Rule-based fallback resolved {} transfers waiting for the ML check", resolved);
        }
        return resolved;
    }
}
