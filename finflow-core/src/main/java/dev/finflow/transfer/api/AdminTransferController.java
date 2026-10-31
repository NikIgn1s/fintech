package dev.finflow.transfer.api;

import dev.finflow.auth.api.AuthenticatedUser;
import dev.finflow.transfer.domain.TransferStatus;
import dev.finflow.transfer.service.TransferReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/transfers")
@PreAuthorize("hasAnyRole('ANALYST', 'ADMIN')")
@RequiredArgsConstructor
public class AdminTransferController {

    private final TransferReviewService reviewService;

    @GetMapping
    public AdminTransferPage list(@RequestParam(defaultValue = "ON_HOLD") TransferStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return reviewService.list(status, page, size);
    }

    @PostMapping("/{id}/approve")
    public AdminTransferResponse approve(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID id) {
        return reviewService.approve(principal.userId(), id);
    }

    @PostMapping("/{id}/reject")
    public AdminTransferResponse reject(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID id, @Valid @RequestBody RejectTransferRequest request) {
        return reviewService.reject(principal.userId(), id, request.reason());
    }
}
