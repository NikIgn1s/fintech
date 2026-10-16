package dev.finflow.ledger.domain;

import dev.finflow.common.error.BusinessRuleException;
import java.math.BigDecimal;

public class InsufficientFundsException extends BusinessRuleException {

    public InsufficientFundsException(Long accountId, BigDecimal balance, BigDecimal required) {
        super("insufficient-funds", "Insufficient funds",
                "Account " + accountId + " balance " + balance.toPlainString()
                        + " is less than " + required.toPlainString());
    }
}
