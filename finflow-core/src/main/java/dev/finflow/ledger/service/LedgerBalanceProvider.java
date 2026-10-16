package dev.finflow.ledger.service;

import dev.finflow.account.api.AccountBalanceProvider;
import dev.finflow.ledger.repository.AccountBalanceRepository;
import dev.finflow.ledger.repository.BalanceView;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LedgerBalanceProvider implements AccountBalanceProvider {

    private final AccountBalanceRepository balances;

    @Override
    public Map<Long, BigDecimal> balancesOf(Collection<Long> accountIds) {
        return balances.findViewsByAccountIdIn(accountIds).stream()
                .collect(Collectors.toMap(BalanceView::getAccountId, BalanceView::getBalance));
    }
}
