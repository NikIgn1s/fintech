package dev.finflow.ledger.api;

import dev.finflow.auth.security.AuthenticatedUser;
import dev.finflow.ledger.service.StatementService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/statement")
@RequiredArgsConstructor
public class StatementController {

    private static final String DEFAULT_LIMIT = "50";
    private static final int MAX_LIMIT = 200;

    private final StatementService statementService;

    @GetMapping
    public StatementPage statement(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Long after,
            @RequestParam(defaultValue = DEFAULT_LIMIT) @Min(1) @Max(MAX_LIMIT) int limit) {
        return statementService.statement(principal.userId(), accountId, from, to, after, limit);
    }
}
