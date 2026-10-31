package dev.finflow.transfer.service;

import dev.finflow.account.api.AccountAccess;
import dev.finflow.common.messaging.EventTypes;
import dev.finflow.common.messaging.Topics;
import dev.finflow.common.outbox.OutboxPublisher;
import dev.finflow.transfer.domain.Transfer;
import dev.finflow.transfer.messaging.TransferStatusChanged;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class TransferEventPublisher {

    private final AccountAccess accounts;
    private final OutboxPublisher outbox;

    @Transactional(propagation = Propagation.MANDATORY)
    public void statusChanged(Transfer transfer, String decidedBy, Instant now) {
        var event = new TransferStatusChanged(transfer.getId(), transfer.getStatus(), transfer.getInitiatedBy(),
                accounts.getTransferTarget(transfer.getToAccountId()).userId(), transfer.getAmount(),
                transfer.getCurrency(), decidedBy, now);
        outbox.publish("transfer", transfer.getId().toString(), EventTypes.TRANSFER_STATUS_CHANGED,
                Topics.TRANSFERS_STATUS_CHANGED, event);
    }
}
