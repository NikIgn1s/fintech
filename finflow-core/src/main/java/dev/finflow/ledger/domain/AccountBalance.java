package dev.finflow.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "account_balances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountBalance {

    @Id
    @Column(name = "account_id")
    private Long accountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(name = "allow_negative", nullable = false)
    private boolean allowNegative;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void apply(BigDecimal delta, Instant now) {
        BigDecimal next = balance.add(delta);
        if (!allowNegative && next.signum() < 0) {
            throw new InsufficientFundsException(accountId, balance, delta.negate());
        }
        this.balance = next;
        this.updatedAt = now;
    }
}
