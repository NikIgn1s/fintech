package dev.finflow.ledger.repository;

import java.math.BigDecimal;

public interface BalanceView {

    Long getAccountId();

    BigDecimal getBalance();
}
