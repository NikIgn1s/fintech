package dev.finflow.ledger.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finflow.common.money.Money;
import dev.finflow.ledger.domain.AccountBalance;
import dev.finflow.ledger.domain.InsufficientFundsException;
import dev.finflow.ledger.domain.LedgerTransaction;
import dev.finflow.ledger.domain.LedgerTransactionType;
import dev.finflow.ledger.domain.Posting;
import dev.finflow.support.AbstractPostgresTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;


@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LedgerRepositoriesTest extends AbstractPostgresTest {

    @Autowired
    private AccountBalanceRepository balances;

    @Autowired
    private LedgerTransactionRepository transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private long systemAccount(String type) {
        return jdbc.queryForObject("select id from accounts where type = ? and currency = 'RUB' and user_id is null", Long.class, type);
    }

    @Test
    void readsSeededSystemBalance() {
        AccountBalance gateway = balances.findById(systemAccount("SYSTEM_GATEWAY")).orElseThrow();

        assertThat(gateway.isAllowNegative()).isTrue();
        assertThat(gateway.getBalance()).isNotNull();
    }

    @Test
    void locksBalancesInAscendingAccountOrderRegardlessOfInputOrder() {
        long gateway = systemAccount("SYSTEM_GATEWAY");
        long suspense = systemAccount("SYSTEM_SUSPENSE");

        List<AccountBalance> locked = balances.lockAllByIdsOrdered(List.of(suspense, gateway));

        assertThat(locked).extracting(AccountBalance::getAccountId).isSorted().hasSize(2);
    }

    @Test
    void appliesDeltaAndGuardsAgainstOverdraft() {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into users (id, email, password_hash, full_name) values (?, ?, 'hash', 'Test')",
                userId, userId + "@example.com");
        long account = jdbc.queryForObject(
                "insert into accounts (user_id, type, currency) values (?, 'USER_WALLET', 'RUB') returning id",
                Long.class, userId);
        AccountBalance balance = balances.findById(account).orElseThrow();

        balance.apply(new BigDecimal("50"), Instant.now());

        assertThat(balance.getBalance()).isEqualByComparingTo("50");
        assertThatThrownBy(() -> balance.apply(new BigDecimal("-50.01"), Instant.now()))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void persistsTransactionWithEntriesAndFindsByReference() {
        long gateway = systemAccount("SYSTEM_GATEWAY");
        long suspense = systemAccount("SYSTEM_SUSPENSE");
        UUID reference = UUID.randomUUID();
        List<Posting> postings = List.of(
                new Posting(gateway, Money.of("-10", "RUB")),
                new Posting(suspense, Money.of("10", "RUB")));

        LedgerTransaction saved = transactions.saveAndFlush(
                LedgerTransaction.of(LedgerTransactionType.TOP_UP, reference, postings, Instant.now()));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getEntries()).hasSize(2).allSatisfy(entry -> assertThat(entry.getId()).isNotNull());
        assertThat(transactions.existsByTypeAndReferenceId(LedgerTransactionType.TOP_UP, reference)).isTrue();
        assertThat(transactions.existsByTypeAndReferenceId(LedgerTransactionType.WITHDRAWAL, reference)).isFalse();
    }
}
