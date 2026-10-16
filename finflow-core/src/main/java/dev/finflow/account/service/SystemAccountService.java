package dev.finflow.account.service;

import dev.finflow.account.domain.Account;
import dev.finflow.account.domain.AccountType;
import dev.finflow.account.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SystemAccountService {

    private final AccountRepository accounts;

    @Transactional(readOnly = true)
    public Long gatewayFor(String currency) {
        return find(AccountType.SYSTEM_GATEWAY, currency);
    }

    @Transactional(readOnly = true)
    public Long suspenseFor(String currency) {
        return find(AccountType.SYSTEM_SUSPENSE, currency);
    }

    private Long find(AccountType type, String currency) {
        return accounts.findByTypeAndCurrencyAndUserIdIsNull(type, currency)
                .map(Account::getId)
                .orElseThrow(() -> new IllegalStateException("System account " + type + " " + currency + " is missing"));
    }
}
