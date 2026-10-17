package dev.finflow.transfer.service;

import dev.finflow.account.api.AccountRef;
import dev.finflow.account.api.AccountResponse;
import dev.finflow.common.money.Money;
import dev.finflow.transfer.domain.Transfer;
import dev.finflow.transfer.domain.TransferStatus;
import dev.finflow.transfer.messaging.TransferCheckRequested;
import dev.finflow.transfer.repository.TransferRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class FraudFeatureCalculator {

    private static final List<TransferStatus> COUNTED_STATUSES = List.of(
            TransferStatus.PENDING_CHECK, TransferStatus.ON_HOLD, TransferStatus.COMPLETED);

    private final TransferRepository transfers;

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public TransferCheckRequested calculate(UUID senderId, AccountResponse source, AccountRef target,
            Transfer transfer, Money amount) {
        Instant now = transfer.getCreatedAt();
        String currency = amount.currency().getCurrencyCode();
        Instant hourAgo = now.minus(Duration.ofHours(1));
        Instant dayAgo = now.minus(Duration.ofHours(24));
        Instant monthAgo = now.minus(Duration.ofDays(30));

        long monthCount = transfers.countInitiatedSince(senderId, currency, monthAgo, COUNTED_STATUSES);
        BigDecimal monthSum = transfers.sumInitiatedSince(senderId, currency, monthAgo, COUNTED_STATUSES)
                .setScale(Money.SCALE);
        BigDecimal average = monthCount == 0
                ? BigDecimal.ZERO.setScale(Money.SCALE)
                : monthSum.divide(BigDecimal.valueOf(monthCount), Money.SCALE, RoundingMode.HALF_EVEN);

        return new TransferCheckRequested(
                transfer.getId(),
                senderId,
                target.userId(),
                amount.amount(),
                currency,
                ageInDays(source.createdAt(), now),
                !transfers.existsByInitiatedByAndToAccountIdAndStatus(senderId, target.id(), TransferStatus.COMPLETED),
                transfers.countInitiatedSince(senderId, currency, hourAgo, COUNTED_STATUSES),
                transfers.countInitiatedSince(senderId, currency, dayAgo, COUNTED_STATUSES),
                transfers.sumInitiatedSince(senderId, currency, dayAgo, COUNTED_STATUSES).setScale(Money.SCALE),
                average,
                now.atOffset(ZoneOffset.UTC).getHour(),
                ageInDays(target.createdAt(), now),
                transfers.countIncomingSince(target.id(), dayAgo, COUNTED_STATUSES),
                now);
    }

    private long ageInDays(Instant createdAt, Instant now) {
        return Math.max(0, ChronoUnit.DAYS.between(createdAt, now));
    }
}
