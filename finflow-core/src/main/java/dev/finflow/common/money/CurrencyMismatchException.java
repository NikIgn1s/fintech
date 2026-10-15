package dev.finflow.common.money;

import java.util.Currency;

public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(Currency left, Currency right) {
        super("Currency mismatch: " + left.getCurrencyCode() + " and " + right.getCurrencyCode());
    }
}
