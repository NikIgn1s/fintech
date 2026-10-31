package dev.finflow.ledger.api;

import dev.finflow.common.money.Money;
import java.util.Objects;

public record Posting(Long accountId, Money amount) {

    public Posting {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
        if (amount.isZero()) {
            throw new IllegalArgumentException("Posting amount must not be zero");
        }
    }
}
