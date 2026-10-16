package dev.finflow.transfer.service;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("finflow.transfer")
public record TransferLimits(
        @DefaultValue("1000000") BigDecimal maxAmount,
        @DefaultValue("5000000") BigDecimal dailyLimit) {
}
