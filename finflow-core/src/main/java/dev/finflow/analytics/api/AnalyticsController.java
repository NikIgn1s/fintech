package dev.finflow.analytics.api;

import dev.finflow.analytics.api.AnalyticsResponse.*;
import dev.finflow.analytics.service.AnalyticsService;
import dev.finflow.auth.security.AuthenticatedUser;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {
    private final AnalyticsService service;

    @GetMapping("/spending")
    public List<Spending> spending(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam LocalDate from,@RequestParam LocalDate to,
            @RequestParam(defaultValue="RUB") String currency,
            @RequestParam(defaultValue="category") String groupBy) {
        return service.spending(user.userId(),from,to,currency,groupBy);
    }

    @GetMapping("/trends")
    public List<Trend> trends(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue="6") int months,@RequestParam(defaultValue="RUB") String currency) {
        return service.trends(user.userId(),months,currency);
    }

    @GetMapping("/top-merchants")
    public List<Merchant> merchants(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam LocalDate from,@RequestParam LocalDate to,
            @RequestParam(defaultValue="RUB") String currency) {
        return service.merchants(user.userId(),from,to,currency);
    }

    @GetMapping("/monthly-spending")
    public List<Monthly> monthly(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue="12") int months,@RequestParam(defaultValue="RUB") String currency) {
        return service.monthly(user.userId(),months,currency);
    }
}
