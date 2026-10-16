package dev.finflow.account.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

public interface AccountBalanceProvider {

    Map<Long, BigDecimal> balancesOf(Collection<Long> accountIds);
}
