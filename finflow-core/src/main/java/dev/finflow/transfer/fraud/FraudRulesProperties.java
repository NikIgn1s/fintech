package dev.finflow.transfer.fraud;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("finflow.fraud.rules")
public record FraudRulesProperties(
        @DefaultValue("50000") BigDecimal largeAmountNewAccount,
        @DefaultValue("7") int newAccountDays,
        @DefaultValue("10") int maxTransfersPerHour,
        @DefaultValue("20000") BigDecimal nightAmount,
        @DefaultValue("6") int nightEndHour) {
}
