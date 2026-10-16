package dev.finflow.transfer.domain;

import dev.finflow.common.id.UuidV7;
import dev.finflow.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "transfers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transfer {

    @Id
    private UUID id;

    @Column(name = "from_account_id", nullable = false, updatable = false)
    private Long fromAccountId;

    @Column(name = "to_account_id", nullable = false, updatable = false)
    private Long toAccountId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status;

    @Column(length = 255, updatable = false)
    private String description;

    @Column(name = "fraud_score", precision = 5, scale = 4)
    private BigDecimal fraudScore;

    @Column(name = "hold_reason", length = 255)
    private String holdReason;

    @Column(name = "initiated_by", nullable = false, updatable = false)
    private UUID initiatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static Transfer create(Long fromAccountId, Long toAccountId, Money amount, String description,
            UUID initiatedBy, Instant now) {
        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("Transfer source and destination must differ");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Transfer amount must be positive");
        }
        Transfer transfer = new Transfer();
        transfer.id = UuidV7.generate();
        transfer.fromAccountId = fromAccountId;
        transfer.toAccountId = toAccountId;
        transfer.amount = amount.amount();
        transfer.currency = amount.currency().getCurrencyCode();
        transfer.status = TransferStatus.PENDING_CHECK;
        transfer.description = description;
        transfer.initiatedBy = initiatedBy;
        transfer.createdAt = now;
        transfer.updatedAt = now;
        return transfer;
    }

    public void complete(BigDecimal score, Instant now) {
        moveTo(TransferStatus.COMPLETED, now);
        if (score != null) {
            this.fraudScore = score;
        }
    }

    public void hold(String reason, BigDecimal score, Instant now) {
        moveTo(TransferStatus.ON_HOLD, now);
        this.holdReason = reason;
        this.fraudScore = score;
    }

    public void reject(String reason, Instant now) {
        moveTo(TransferStatus.REJECTED, now);
        if (reason != null) {
            this.holdReason = reason;
        }
    }

    public void fail(String reason, Instant now) {
        moveTo(TransferStatus.FAILED, now);
        this.holdReason = reason;
    }

    private void moveTo(TransferStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalTransferStateException(id, status, target);
        }
        this.status = target;
        this.updatedAt = now;
    }
}
