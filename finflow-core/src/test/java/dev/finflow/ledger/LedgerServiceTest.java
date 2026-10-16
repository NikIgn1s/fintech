package dev.finflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finflow.common.money.CurrencyMismatchException;
import dev.finflow.common.money.Money;
import dev.finflow.ledger.domain.DuplicatePostingException;
import dev.finflow.ledger.domain.InsufficientFundsException;
import dev.finflow.ledger.domain.LedgerTransaction;
import dev.finflow.ledger.domain.LedgerTransactionType;
import dev.finflow.ledger.domain.Posting;
import dev.finflow.ledger.domain.UnbalancedPostingException;
import dev.finflow.support.AbstractPostgresTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class LedgerServiceTest extends AbstractPostgresTest {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private long newUserAccount() {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into users (id, email, password_hash, full_name) values (?, ?, 'hash', 'Test')",
                userId, userId + "@example.com");
        return jdbc.queryForObject(
                "insert into accounts (user_id, type, currency) values (?, 'USER_WALLET', 'RUB') returning id",
                Long.class, userId);
    }

    private long systemAccount(String type) {
        return jdbc.queryForObject("select id from accounts where type = ?", Long.class, type);
    }

    private BigDecimal balanceOf(long accountId) {
        return jdbc.queryForObject("select balance from account_balances where account_id = ?",
                BigDecimal.class, accountId);
    }

    private int ledgerTransactionCount(UUID referenceId) {
        return jdbc.queryForObject("select count(*) from ledger_transactions where reference_id = ?",
                Integer.class, referenceId);
    }

    private Posting rub(long accountId, String amount) {
        return new Posting(accountId, Money.of(amount, "RUB"));
    }

    private LedgerTransaction topUp(long accountId, String amount) {
        long gateway = systemAccount("SYSTEM_GATEWAY");
        return tx().execute(status -> ledger.post(LedgerTransactionType.TOP_UP, UUID.randomUUID(),
                List.of(rub(gateway, "-" + amount), rub(accountId, amount))));
    }

    @Test
    void postUpdatesBalancesAndWritesEntries() {
        long alice = newUserAccount();

        LedgerTransaction posted = topUp(alice, "100.50");

        assertThat(balanceOf(alice)).isEqualByComparingTo("100.50");
        Integer entries = jdbc.queryForObject("select count(*) from ledger_entries where transaction_id = ?",
                Integer.class, posted.getId());
        assertThat(entries).isEqualTo(2);
    }

    @Test
    void requiresSurroundingTransaction() {
        long alice = newUserAccount();
        long gateway = systemAccount("SYSTEM_GATEWAY");

        assertThatThrownBy(() -> ledger.post(LedgerTransactionType.TOP_UP, UUID.randomUUID(),
                List.of(rub(gateway, "-1"), rub(alice, "1"))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void rejectsUnbalancedPostings() {
        long alice = newUserAccount();
        long gateway = systemAccount("SYSTEM_GATEWAY");

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> ledger.post(LedgerTransactionType.TOP_UP,
                UUID.randomUUID(), List.of(rub(gateway, "-10"), rub(alice, "9")))))
                .isInstanceOf(UnbalancedPostingException.class);
        assertThat(balanceOf(alice)).isEqualByComparingTo("0");
    }

    @Test
    void rejectsSinglePosting() {
        long alice = newUserAccount();

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> ledger.post(LedgerTransactionType.TOP_UP,
                UUID.randomUUID(), List.of(rub(alice, "10")))))
                .isInstanceOf(UnbalancedPostingException.class);
    }

    @Test
    void rejectsMixedCurrencies() {
        long alice = newUserAccount();
        long gateway = systemAccount("SYSTEM_GATEWAY");

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> ledger.post(LedgerTransactionType.TOP_UP,
                UUID.randomUUID(), List.of(rub(gateway, "-10"), new Posting(alice, Money.of("10", "USD"))))))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void overdraftRollsBackEverything() {
        long alice = newUserAccount();
        long suspense = systemAccount("SYSTEM_SUSPENSE");
        BigDecimal suspenseBefore = balanceOf(suspense);
        UUID reference = UUID.randomUUID();

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> ledger.post(
                LedgerTransactionType.TRANSFER_RESERVE, reference, List.of(rub(alice, "-10"), rub(suspense, "10")))))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balanceOf(alice)).isEqualByComparingTo("0");
        assertThat(balanceOf(suspense)).isEqualByComparingTo(suspenseBefore);
        assertThat(ledgerTransactionCount(reference)).isZero();
    }

    @Test
    void rejectsDuplicateReferenceWithoutChangingBalances() {
        long alice = newUserAccount();
        long gateway = systemAccount("SYSTEM_GATEWAY");
        UUID reference = UUID.randomUUID();
        List<Posting> postings = List.of(rub(gateway, "-25"), rub(alice, "25"));
        tx().executeWithoutResult(status -> ledger.post(LedgerTransactionType.TOP_UP, reference, postings));

        assertThatThrownBy(() -> tx().executeWithoutResult(
                status -> ledger.post(LedgerTransactionType.TOP_UP, reference, postings)))
                .isInstanceOf(DuplicatePostingException.class);

        assertThat(balanceOf(alice)).isEqualByComparingTo("25");
    }

    @Test
    void rejectsUnknownAccount() {
        long gateway = systemAccount("SYSTEM_GATEWAY");

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> ledger.post(LedgerTransactionType.TOP_UP,
                UUID.randomUUID(), List.of(rub(gateway, "-1"), rub(987_654_321L, "1")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void netsPostingsOfTheSameAccountBeforeCheckingFunds() {
        long alice = newUserAccount();
        long suspense = systemAccount("SYSTEM_SUSPENSE");
        topUp(alice, "100");

        tx().executeWithoutResult(status -> ledger.post(LedgerTransactionType.TRANSFER_RESERVE, UUID.randomUUID(),
                List.of(rub(alice, "-100"), rub(alice, "30"), rub(suspense, "70"))));

        assertThat(balanceOf(alice)).isEqualByComparingTo("30");
    }
}
