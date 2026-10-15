package dev.finflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finflow.support.AbstractLedgerSchemaTest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class LedgerSchemaTest extends AbstractLedgerSchemaTest {

    @Test
    void systemAccountsAreSeededAndMayGoNegative() {
        long gateway = systemAccount("SYSTEM_GATEWAY");
        long suspense = systemAccount("SYSTEM_SUSPENSE");

        Boolean gatewayAllowsNegative = jdbc.queryForObject(
                "select allow_negative from account_balances where account_id = ?", Boolean.class, gateway);
        Boolean suspenseAllowsNegative = jdbc.queryForObject(
                "select allow_negative from account_balances where account_id = ?", Boolean.class, suspense);

        assertThat(gatewayAllowsNegative).isTrue();
        assertThat(suspenseAllowsNegative).isTrue();
    }

    @Test
    void newUserAccountGetsZeroBalanceThatCannotGoNegative() {
        long account = newUserAccount("RUB");

        BigDecimal balance = jdbc.queryForObject(
                "select balance from account_balances where account_id = ?", BigDecimal.class, account);
        Boolean allowNegative = jdbc.queryForObject(
                "select allow_negative from account_balances where account_id = ?", Boolean.class, account);

        assertThat(balance).isEqualByComparingTo("0");
        assertThat(allowNegative).isFalse();
    }

    @Test
    void userAccountBalanceCannotBecomeNegative() {
        long account = newUserAccount("RUB");

        assertThatThrownBy(() -> jdbc.update("update account_balances set balance = -0.01 where account_id = ?", account))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsSecondGatewayAccount() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into accounts (user_id, type, currency) values (null, 'SYSTEM_GATEWAY', 'RUB')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUserTypeWithoutOwner() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into accounts (user_id, type, currency) values (null, 'USER_WALLET', 'RUB')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateTypeAndReference() {
        UUID reference = UUID.randomUUID();
        jdbc.update("insert into ledger_transactions (type, reference_id) values ('TOP_UP', ?)", reference);

        assertThatThrownBy(() -> jdbc.update(
                "insert into ledger_transactions (type, reference_id) values ('TOP_UP', ?)", reference))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsSameReferenceForDifferentTypes() {
        UUID reference = UUID.randomUUID();
        jdbc.update("insert into ledger_transactions (type, reference_id) values ('TRANSFER_RESERVE', ?)", reference);
        jdbc.update("insert into ledger_transactions (type, reference_id) values ('TRANSFER_SETTLE', ?)", reference);

        Integer count = jdbc.queryForObject(
                "select count(*) from ledger_transactions where reference_id = ?", Integer.class, reference);

        assertThat(count).isEqualTo(2);
    }

    @Test
    void rejectsUnknownTransactionType() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into ledger_transactions (type, reference_id) values ('MAGIC', ?)", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsZeroAmountEntry() {
        long account = newUserAccount("RUB");
        long transaction = newLedgerTransaction("TOP_UP");

        assertThatThrownBy(() -> addEntry(transaction, account, "0", "RUB"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
