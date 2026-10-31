package dev.finflow.account.api;

import dev.finflow.account.service.AccountService;
import dev.finflow.auth.api.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse open(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody OpenAccountRequest request) {
        return accountService.open(principal.userId(), request);
    }

    @GetMapping
    public List<AccountResponse> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return accountService.listOwn(principal.userId());
    }

    @GetMapping("/{id}")
    public AccountResponse get(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long id) {
        return accountService.getOwn(principal.userId(), id);
    }
}
