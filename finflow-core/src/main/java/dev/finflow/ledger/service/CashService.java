package dev.finflow.ledger.service;

import dev.finflow.account.api.AccountResponse;
import dev.finflow.account.domain.AccountStatus;
import dev.finflow.account.service.AccountService;
import dev.finflow.account.service.SystemAccountService;
import dev.finflow.common.error.BusinessRuleException;
import dev.finflow.common.id.UuidV7;
import dev.finflow.common.money.Money;
import dev.finflow.ledger.LedgerService;
import dev.finflow.ledger.api.CashOperationRequest;
import dev.finflow.ledger.api.CashOperationResponse;
import dev.finflow.ledger.domain.LedgerTransaction;
import dev.finflow.ledger.domain.LedgerTransactionType;
import dev.finflow.ledger.domain.Posting;
import dev.finflow.ledger.repository.AccountBalanceRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CashService {

    private final AccountService accounts;
    private final SystemAccountService systemAccounts;
    private final LedgerService ledger;
    private final AccountBalanceRepository balances;

    @Transactional
    public CashOperationResponse topUp(UUID userId, CashOperationRequest request) {
        return execute(LedgerTransactionType.TOP_UP, userId, request, true);
    }

    @Transactional
    public CashOperationResponse withdraw(UUID userId, CashOperationRequest request) {
        return execute(LedgerTransactionType.WITHDRAWAL, userId, request, false);
    }

    private CashOperationResponse execute(LedgerTransactionType type, UUID userId, CashOperationRequest request,
            boolean credit) {
        AccountResponse account = accounts.getOwn(userId, request.accountId());
        if (account.status() != AccountStatus.ACTIVE) {
            throw new BusinessRuleException("account-closed", "Account closed",
                    "Account " + account.id() + " is not active");
        }
        Money amount = Money.of(request.amount(), account.currency());
        Money delta = credit ? amount : amount.negate();
        UUID operationId = UuidV7.generate();
        Long gateway = systemAccounts.gatewayFor(account.currency());

        LedgerTransaction posted = ledger.post(type, operationId, List.of(
                new Posting(account.id(), delta),
                new Posting(gateway, delta.negate())));

        BigDecimal balance = balances.findById(account.id()).orElseThrow().getBalance();
        return new CashOperationResponse(operationId, type, account.id(), amount.amount(), account.currency(),
                balance, posted.getCreatedAt());
    }
}
