package dev.finflow.analytics.api;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class AnalyticsResponse {
    private AnalyticsResponse() {}
    public record Spending(LocalDate month, Short categoryId, String category, BigDecimal spent,
            String currency, String level) {}
    public record Trend(LocalDate month, Short categoryId, BigDecimal spent, BigDecimal previousMonth,
            BigDecimal changePercent, BigDecimal movingAverage3m, String currency) {}
    public record Merchant(Short categoryId, String merchant, BigDecimal spent, long rank, String currency) {}
    public record Monthly(LocalDate month, Short categoryId, BigDecimal spent, long transactionCount, String currency) {}
}
