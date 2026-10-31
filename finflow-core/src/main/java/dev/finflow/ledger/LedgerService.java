package dev.finflow.ledger;

import dev.finflow.common.money.Money;
import dev.finflow.ledger.domain.AccountBalance;
import dev.finflow.ledger.domain.DuplicatePostingException;
import dev.finflow.ledger.domain.LedgerTransaction;
import dev.finflow.ledger.api.LedgerTransactionType;
import dev.finflow.ledger.api.Posting;
import dev.finflow.ledger.domain.UnbalancedPostingException;
import dev.finflow.ledger.repository.AccountBalanceRepository;
import dev.finflow.ledger.repository.LedgerTransactionRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private static final int MIN_POSTINGS = 2;

    private final AccountBalanceRepository balances;
    private final LedgerTransactionRepository transactions;
    private final EntityManager entityManager;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerTransaction post(LedgerTransactionType type, UUID referenceId, List<Posting> postings) {
        requireValid(type, referenceId, postings);
        if (transactions.existsByTypeAndReferenceId(type, referenceId)) {
            throw new DuplicatePostingException(type, referenceId);
        }

        Map<Long, BigDecimal> netByAccount = netByAccount(postings);
        List<AccountBalance> lockedRows = balances.lockAllByIdsOrdered(netByAccount.keySet());
        lockedRows.forEach(entityManager::refresh);
        Map<Long, AccountBalance> locked = lockedRows.stream()
                .collect(Collectors.toMap(AccountBalance::getAccountId, Function.identity()));
        if (locked.size() != netByAccount.size()) {
            throw new IllegalArgumentException("Unknown accounts in postings: " + netByAccount.keySet());
        }

        Instant now = clock.instant();
        netByAccount.forEach((accountId, delta) -> locked.get(accountId).apply(delta, now));
        return transactions.save(LedgerTransaction.of(type, referenceId, postings, now));
    }

    private void requireValid(LedgerTransactionType type, UUID referenceId, List<Posting> postings) {
        if (postings == null || postings.size() < MIN_POSTINGS) {
            throw new UnbalancedPostingException(type, referenceId, "at least two postings are required");
        }
        Money total = postings.stream().map(Posting::amount).reduce(Money::add).orElseThrow();
        if (!total.isZero()) {
            throw new UnbalancedPostingException(type, referenceId, "postings sum to " + total);
        }
    }

    private Map<Long, BigDecimal> netByAccount(List<Posting> postings) {
        return postings.stream().collect(Collectors.toMap(
                Posting::accountId,
                posting -> posting.amount().amount(),
                BigDecimal::add,
                TreeMap::new));
    }
}
