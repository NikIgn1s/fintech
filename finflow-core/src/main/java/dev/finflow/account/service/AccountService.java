package dev.finflow.account.service;

import dev.finflow.account.api.AccountBalanceProvider;
import dev.finflow.account.api.AccountMapper;
import dev.finflow.account.api.AccountRef;
import dev.finflow.account.api.AccountResponse;
import dev.finflow.account.api.OpenAccountRequest;
import dev.finflow.account.domain.Account;
import dev.finflow.account.repository.AccountRepository;
import dev.finflow.common.error.BusinessRuleException;
import dev.finflow.common.error.NotFoundException;
import dev.finflow.common.money.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountService implements dev.finflow.account.api.AccountAccess {

    static final String DEFAULT_CURRENCY = "RUB";
    static final Set<String> SUPPORTED_CURRENCIES = Set.of("RUB", "USD", "EUR");
    static final int MAX_ACCOUNTS_PER_USER = 10;

    private static final BigDecimal ZERO_BALANCE = BigDecimal.ZERO.setScale(Money.SCALE);

    private final AccountRepository accounts;
    private final AccountMapper mapper;
    private final AccountBalanceProvider balanceProvider;

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
        return mapper.toResponse(accounts.save(account), ZERO_BALANCE);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> listOwn(UUID userId) {
        List<Account> own = accounts.findByUserIdOrderById(userId);
        Map<Long, BigDecimal> balances = balanceProvider.balancesOf(own.stream().map(Account::getId).toList());
        return own.stream()
                .map(account -> mapper.toResponse(account, balances.getOrDefault(account.getId(), ZERO_BALANCE)))
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountRef getTransferTarget(Long accountId) {
        return accounts.findById(accountId)
                .filter(account -> !account.getType().isSystem())
                .map(account -> new AccountRef(account.getId(), account.getUserId(), account.getCurrency(),
                        account.getStatus(), account.getCreatedAt()))
                .orElseThrow(() -> new NotFoundException("Recipient account " + accountId + " not found"));
    }

    @Transactional(readOnly = true)
    public AccountResponse getOwn(UUID userId, Long accountId) {
        Account account = accounts.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new NotFoundException("Account " + accountId + " not found"));
        BigDecimal balance = balanceProvider.balancesOf(List.of(accountId)).getOrDefault(accountId, ZERO_BALANCE);
        return mapper.toResponse(account, balance);
    }
}
