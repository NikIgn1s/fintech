package dev.finflow.common.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("finflow.outbox")
public record OutboxProperties(
        @DefaultValue("100") int batchSize,
        @DefaultValue("5s") Duration sendTimeout,
        @DefaultValue("7d") Duration retention) {
}
