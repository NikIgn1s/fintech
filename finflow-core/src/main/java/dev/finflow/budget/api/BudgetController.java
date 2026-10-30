package dev.finflow.budget.api;

import dev.finflow.auth.security.AuthenticatedUser;
import dev.finflow.budget.service.BudgetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/budgets")
@RequiredArgsConstructor
public class BudgetController {
    private final BudgetService service;
    public record Request(@Positive short categoryId,
            @NotNull @Pattern(regexp="RUB|USD|EUR") String currency,
            @NotNull @Positive @Digits(integer=15, fraction=4) BigDecimal limit) {}

    @GetMapping
    public List<BudgetService.Budget> list(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam String month) {
        return service.list(user.userId(), parse(month));
    }

    @PutMapping
    public BudgetService.Budget save(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam String month, @Valid @RequestBody Request request) {
        return service.save(user.userId(), request.categoryId(), parse(month), request.currency(), request.limit());
    }

    private LocalDate parse(String month) {
        try {
            return YearMonth.parse(month).atDay(1);
        } catch (java.time.format.DateTimeParseException error) {
            throw new dev.finflow.common.error.BusinessRuleException("invalid-month", "Invalid month", "Expected YYYY-MM");
        }
    }
}
