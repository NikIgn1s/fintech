package dev.finflow.transfer.service;

import dev.finflow.account.api.SystemAccounts;
import dev.finflow.common.money.Money;
import dev.finflow.ledger.api.LedgerOperations;
import dev.finflow.ledger.api.LedgerTransactionType;
import dev.finflow.ledger.api.Posting;
import dev.finflow.transfer.domain.Transfer;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class TransferSettlement {

    private final LedgerOperations ledger;
    private final SystemAccounts systemAccounts;

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
