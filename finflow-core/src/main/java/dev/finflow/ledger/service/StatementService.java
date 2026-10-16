package dev.finflow.ledger.service;

import dev.finflow.account.api.AccountResponse;
import dev.finflow.account.service.AccountService;
import dev.finflow.ledger.api.StatementItem;
import dev.finflow.ledger.api.StatementPage;
import dev.finflow.ledger.domain.LedgerEntry;
import dev.finflow.ledger.repository.LedgerEntryRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StatementService {

    private static final Instant MIN_TIME = Instant.EPOCH;
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59Z");

    private final AccountService accounts;
    private final LedgerEntryRepository entries;

    @Transactional(readOnly = true)
    public StatementPage statement(UUID userId, Long accountId, Instant from, Instant to, Long after, int limit) {
        AccountResponse account = accounts.getOwn(userId, accountId);
        List<LedgerEntry> page = entries.findPage(
                accountId,
                after == null ? Long.MAX_VALUE : after,
                from == null ? MIN_TIME : from,
                to == null ? MAX_TIME : to,
                PageRequest.of(0, limit + 1));

        boolean hasMore = page.size() > limit;
        List<LedgerEntry> visible = hasMore ? page.subList(0, limit) : page;
        List<StatementItem> items = visible.stream().map(this::toItem).toList();
        Long nextCursor = hasMore ? visible.get(visible.size() - 1).getId() : null;
        return new StatementPage(account.id(), account.currency(), account.balance(), items, nextCursor);
    }

    private StatementItem toItem(LedgerEntry entry) {
        return new StatementItem(entry.getId(), entry.getTransaction().getType(),
                entry.getTransaction().getReferenceId(), entry.getAmount(), entry.getCreatedAt());
    }
}
