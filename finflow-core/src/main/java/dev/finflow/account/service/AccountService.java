package dev.finflow.account.service;

import dev.finflow.account.api.AccountMapper;
import dev.finflow.account.api.AccountResponse;
import dev.finflow.account.api.OpenAccountRequest;
import dev.finflow.account.domain.Account;
import dev.finflow.account.repository.AccountRepository;
import dev.finflow.common.error.BusinessRuleException;
import dev.finflow.common.error.NotFoundException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountService {

    static final String DEFAULT_CURRENCY = "RUB";
    static final Set<String> SUPPORTED_CURRENCIES = Set.of("RUB", "USD", "EUR");
    static final int MAX_ACCOUNTS_PER_USER = 10;

    private final AccountRepository accounts;
    private final AccountMapper mapper;

    @Transactional
    public AccountResponse open(UUID userId, OpenAccountRequest request) {
        String currency = request.currency() == null ? DEFAULT_CURRENCY : request.currency();
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            throw new BusinessRuleException("unsupported-currency", "Unsupported currency",
                    "Currency " + currency + " is not supported");
        }
        if (accounts.countByUserId(userId) >= MAX_ACCOUNTS_PER_USER) {
            throw new BusinessRuleException("account-limit-reached", "Account limit reached",
                    "A user can have at most " + MAX_ACCOUNTS_PER_USER + " accounts");
        }
        Account account = Account.open(userId, request.type().toAccountType(), currency);
        return mapper.toResponse(accounts.save(account));
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> listOwn(UUID userId) {
        return mapper.toResponses(accounts.findByUserIdOrderById(userId));
    }

    @Transactional(readOnly = true)
    public AccountResponse getOwn(UUID userId, Long accountId) {
        return accounts.findByIdAndUserId(accountId, userId)
                .map(mapper::toResponse)
                .orElseThrow(() -> new NotFoundException("Account " + accountId + " not found"));
    }
}
