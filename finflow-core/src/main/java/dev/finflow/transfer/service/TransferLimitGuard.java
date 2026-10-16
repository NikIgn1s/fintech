package dev.finflow.transfer.service;

import dev.finflow.common.error.BusinessRuleException;
import dev.finflow.common.money.Money;
import dev.finflow.transfer.domain.TransferStatus;
import dev.finflow.transfer.repository.TransferRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class TransferLimitGuard {

    private static final Duration WINDOW = Duration.ofHours(24);
    private static final List<TransferStatus> COUNTED_STATUSES = List.of(
            TransferStatus.PENDING_CHECK, TransferStatus.ON_HOLD, TransferStatus.COMPLETED);

    private final TransferLimits limits;
    private final TransferRepository transfers;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void check(UUID userId, Money amount) {
        if (amount.amount().compareTo(limits.maxAmount()) > 0) {
            throw new BusinessRuleException("transfer-limit-exceeded", "Transfer limit exceeded",
                    "Transfer amount " + amount + " exceeds the limit of " + limits.maxAmount().toPlainString());
        }
        lockUser(userId);
        Instant since = clock.instant().minus(WINDOW);
        BigDecimal spent = transfers.sumInitiatedSince(userId, amount.currency().getCurrencyCode(), since,
                COUNTED_STATUSES);
        if (spent.add(amount.amount()).compareTo(limits.dailyLimit()) > 0) {
            throw new BusinessRuleException("daily-limit-exceeded", "Daily limit exceeded",
                    "Transfers over the last 24 hours would reach " + spent.add(amount.amount()).toPlainString()
                            + " with the limit of " + limits.dailyLimit().toPlainString());
        }
    }

    private void lockUser(UUID userId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, "transfer-limit:" + userId);
    }
}
