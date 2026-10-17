package dev.finflow.common.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("finflow.kafka.retry")
public record KafkaRetryProperties(
        @DefaultValue("500ms") Duration initialInterval,
        @DefaultValue("2.0") double multiplier,
        @DefaultValue("3") int maxRetries) {
}
