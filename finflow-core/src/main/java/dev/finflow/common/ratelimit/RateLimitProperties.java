package dev.finflow.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("finflow.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10") int transfersPerMinute,
        @DefaultValue("20") int cashPerMinute) {

    int limitFor(RateLimitGroup group) {
        return switch (group) {
            case TRANSFERS -> transfersPerMinute;
            case CASH -> cashPerMinute;
        };
    }
}
