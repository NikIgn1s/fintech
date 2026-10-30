package dev.finflow.analytics.service;

import dev.finflow.analytics.api.AnalyticsResponse.*;
import dev.finflow.analytics.repository.AnalyticsRepository;
import dev.finflow.common.error.BusinessRuleException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class AnalyticsService {
    private final AnalyticsRepository repository;
    private final Clock clock;

    public List<Spending> spending(UUID user,LocalDate from,LocalDate to,String currency,String groupBy) {
        range(from,to,currency);
        if (!Set.of("category","month").contains(groupBy)) throw invalid("groupBy must be category or month");
        return repository.spending(user,from,to,currency,groupBy.equals("month"));
    }

    public List<Trend> trends(UUID user,int months,String currency) {
        validateMonths(months,currency);
        LocalDate current=currentMonth();
        return repository.trends(user,current.minusMonths(months-1),current.plusMonths(1),currency);
    }

    public List<Merchant> merchants(UUID user,LocalDate from,LocalDate to,String currency) {
        range(from,to,currency);
        return repository.merchants(user,from,to,currency);
    }

    public List<Monthly> monthly(UUID user,int months,String currency) {
        validateMonths(months,currency);
        LocalDate current=currentMonth();
        return repository.monthly(user,current.minusMonths(months-1),current,currency);
    }

    private LocalDate currentMonth() {
        return LocalDate.ofInstant(clock.instant(),ZoneOffset.UTC).withDayOfMonth(1);
    }

    private void validateMonths(int months,String currency) {
        currency(currency);
        if (months<1 || months>120) throw invalid("months must be between 1 and 120");
    }

    private void range(LocalDate from,LocalDate to,String currency) {
        currency(currency);
        if (!from.isBefore(to) || ChronoUnit.DAYS.between(from,to)>3660)
            throw invalid("Expected from < to and a period of at most 3660 days; to is exclusive");
    }

    private void currency(String currency) {
        if (!Set.of("RUB","USD","EUR").contains(currency)) throw invalid("Unsupported currency");
    }

    private BusinessRuleException invalid(String detail) {
        return new BusinessRuleException("invalid-analytics-query","Invalid analytics query",detail);
    }
}
