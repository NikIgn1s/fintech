package dev.finflow.ledger.api;

import dev.finflow.auth.security.AuthenticatedUser;
import dev.finflow.ledger.service.CashService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CashController {

    private final CashService cashService;

    @PostMapping("/top-ups")
    @ResponseStatus(HttpStatus.CREATED)
    public CashOperationResponse topUp(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CashOperationRequest request) {
        return cashService.topUp(principal.userId(), request);
    }

    @PostMapping("/withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    public CashOperationResponse withdraw(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CashOperationRequest request) {
        return cashService.withdraw(principal.userId(), request);
    }
}
