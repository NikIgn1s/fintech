package dev.finflow.observability;

import io.micrometer.core.instrument.*;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TransferMetrics {
    private final MeterRegistry registry;

    public TransferMetrics(MeterRegistry registry) {
        this.registry=registry;
        registry.counter("transfers.created","status","PENDING_CHECK");
        for (String decision:new String[]{"APPROVE","HOLD"}) {
            for (String source:new String[]{"ML","RULES_FALLBACK"}) {
                registry.counter("fraud.decisions","decision",decision,"source",source);
            }
        }
    }

    @TransactionalEventListener
    public void created(dev.finflow.transfer.api.events.TransferCreated event) {
        registry.counter("transfers.created","status","PENDING_CHECK").increment();
    }

    @TransactionalEventListener
    public void checked(dev.finflow.transfer.api.events.TransferChecked event) {
        registry.counter("fraud.decisions","decision",event.decision(),"source",event.source()).increment();
        Timer.builder("transfer.check.latency").publishPercentileHistogram()
                .tag("source",event.source()).register(registry).record(event.latency().isNegative()?Duration.ZERO:event.latency());
    }
}
