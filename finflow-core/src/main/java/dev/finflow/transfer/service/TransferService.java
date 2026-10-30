package dev.finflow.transfer.service;

import dev.finflow.account.api.AccountRef;
import dev.finflow.account.api.AccountResponse;
import dev.finflow.account.domain.AccountStatus;
import dev.finflow.account.service.AccountService;
import dev.finflow.account.service.SystemAccountService;
import dev.finflow.common.error.BusinessRuleException;
import dev.finflow.common.messaging.EventTypes;
import dev.finflow.common.messaging.Topics;
import dev.finflow.common.money.Money;
import dev.finflow.common.outbox.OutboxPublisher;
import dev.finflow.ledger.LedgerService;
import dev.finflow.ledger.domain.LedgerTransactionType;
import dev.finflow.ledger.domain.Posting;
import dev.finflow.transfer.api.TransferMapper;
import dev.finflow.transfer.api.TransferResponse;
import dev.finflow.transfer.domain.Transfer;
import dev.finflow.transfer.messaging.TransferCheckRequested;
import dev.finflow.transfer.repository.TransferRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountService accounts;
    private final SystemAccountService systemAccounts;
    private final LedgerService ledger;
    private final TransferRepository transfers;
    private final TransferMapper mapper;
    private final TransferLimitGuard limitGuard;
    private final FraudFeatureCalculator featureCalculator;
    private final OutboxPublisher outbox;
    private final Clock clock;
    private final org.springframework.context.ApplicationEventPublisher metricsEvents;

    @Transactional
    public TransferResponse create(UUID userId, CreateTransferCommand command) {
        if (command.fromAccountId().equals(command.toAccountId())) {
            throw new BusinessRuleException("same-account", "Same account",
                    "Source and destination accounts must differ");
        }
        AccountResponse source = accounts.getOwn(userId, command.fromAccountId());
        AccountRef target = accounts.getTransferTarget(command.toAccountId());
        requireActive(source.status(), "account-closed", "Source account " + source.id() + " is not active");
        requireActive(target.status(), "recipient-account-closed",
                "Recipient account " + target.id() + " is not active");
        if (!source.currency().equals(target.currency())) {
            throw new BusinessRuleException("currency-mismatch", "Currency mismatch",
                    "Transfers between " + source.currency() + " and " + target.currency() + " accounts are not supported");
        }

        Money amount = Money.of(command.amount(), source.currency());
        limitGuard.check(userId, amount);
        Transfer draft = Transfer.create(source.id(), target.id(), amount, command.description(), userId,
                clock.instant());
        TransferCheckRequested checkRequest = featureCalculator.calculate(userId, source, target, draft, amount);
        Transfer transfer = transfers.save(draft);

        Long suspense = systemAccounts.suspenseFor(source.currency());
        ledger.post(LedgerTransactionType.TRANSFER_RESERVE, transfer.getId(), List.of(
                new Posting(source.id(), amount.negate()),
                new Posting(suspense, amount)));
        outbox.publish("transfer", transfer.getId().toString(), EventTypes.TRANSFER_CHECK_REQUESTED,
                Topics.TRANSFERS_CHECK, checkRequest);
        metricsEvents.publishEvent(new dev.finflow.observability.TransferMetrics.Created());
        return mapper.toResponse(transfer);
    }

    private void requireActive(AccountStatus status, String code, String detail) {
        if (status != AccountStatus.ACTIVE) {
            throw new BusinessRuleException(code, "Account closed", detail);
        }
    }
}
