package dev.finflow.transfer.api;

import dev.finflow.auth.api.AuthenticatedUser;
import dev.finflow.transfer.domain.TransferStatus;
import dev.finflow.transfer.service.CreateTransferCommand;
import dev.finflow.transfer.service.TransferQueryService;
import dev.finflow.transfer.service.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class TransferController {

    private static final String DEFAULT_LIMIT = "50";
    private static final int MAX_LIMIT = 200;

    private final TransferService transferService;
    private final TransferQueryService queryService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateTransferRequest request) {
        return transferService.create(principal.userId(), new CreateTransferCommand(
                request.fromAccountId(), request.toAccountId(), request.amount(), request.description()));
    }

    @GetMapping("/{id}")
    public TransferResponse get(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID id) {
        return queryService.get(principal.userId(), id);
    }

    @GetMapping
    public TransferPage list(@AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) TransferDirection direction,
            @RequestParam(required = false) TransferStatus status,
            @RequestParam(required = false) UUID after,
            @RequestParam(defaultValue = DEFAULT_LIMIT) @Min(1) @Max(MAX_LIMIT) int limit) {
        return queryService.list(principal.userId(), direction, status, after, limit);
    }
}
