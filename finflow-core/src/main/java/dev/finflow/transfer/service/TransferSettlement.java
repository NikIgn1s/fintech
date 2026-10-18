package dev.finflow.transfer.service;

import dev.finflow.account.service.SystemAccountService;
import dev.finflow.common.money.Money;
import dev.finflow.ledger.LedgerService;
import dev.finflow.ledger.domain.LedgerTransactionType;
import dev.finflow.ledger.domain.Posting;
import dev.finflow.transfer.domain.Transfer;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class TransferSettlement {

    private final LedgerService ledger;
    private final SystemAccountService systemAccounts;

    @Transactional(propagation = Propagation.MANDATORY)
    public void settle(Transfer transfer) {
        Money amount = Money.of(transfer.getAmount(), transfer.getCurrency());
        Long suspense = systemAccounts.suspenseFor(transfer.getCurrency());
        ledger.post(LedgerTransactionType.TRANSFER_SETTLE, transfer.getId(), List.of(
                new Posting(suspense, amount.negate()),
                new Posting(transfer.getToAccountId(), amount)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refund(Transfer transfer) {
        Money amount = Money.of(transfer.getAmount(), transfer.getCurrency());
        Long suspense = systemAccounts.suspenseFor(transfer.getCurrency());
        ledger.post(LedgerTransactionType.TRANSFER_REFUND, transfer.getId(), List.of(
                new Posting(suspense, amount.negate()),
                new Posting(transfer.getFromAccountId(), amount)));
    }
}
