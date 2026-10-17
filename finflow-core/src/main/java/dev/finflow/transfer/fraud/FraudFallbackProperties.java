package dev.finflow.transfer.fraud;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("finflow.fraud.fallback")
public record FraudFallbackProperties(
        @DefaultValue("30s") Duration timeout,
        @DefaultValue("100") int batchSize) {
}
