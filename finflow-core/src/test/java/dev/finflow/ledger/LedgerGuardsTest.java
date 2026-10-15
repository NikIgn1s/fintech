package dev.finflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finflow.support.AbstractLedgerSchemaTest;
import org.junit.jupiter.api.Test;

class LedgerGuardsTest extends AbstractLedgerSchemaTest {

    @Test
    void commitsBalancedTransaction() {
        long alice = newUserAccount("RUB");
        long gateway = systemAccount("SYSTEM_GATEWAY");

        long transaction = tx().execute(status -> {
            long id = newLedgerTransaction("TOP_UP");
            addEntry(id, gateway, "-100.50", "RUB");
            addEntry(id, alice, "100.50", "RUB");
            return id;
        });

        Integer entries = jdbc.queryForObject(
                "select count(*) from ledger_entries where transaction_id = ?", Integer.class, transaction);
        assertThat(entries).isEqualTo(2);
    }

    @Test
    void rejectsUnbalancedTransactionAtCommit() {
        long alice = newUserAccount("RUB");
        long gateway = systemAccount("SYSTEM_GATEWAY");

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            long id = newLedgerTransaction("TOP_UP");
            addEntry(id, gateway, "-100", "RUB");
            addEntry(id, alice, "99", "RUB");
        })).rootCause().hasMessageContaining("is not balanced");
    }

    @Test
    void rejectsSingleSidedTransaction() {
        long alice = newUserAccount("RUB");

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            long id = newLedgerTransaction("TOP_UP");
            addEntry(id, alice, "100", "RUB");
        })).rootCause().hasMessageContaining("is not balanced");
    }

    @Test
    void rejectsEntryInDifferentCurrencyThanAccount() {
        long alice = newUserAccount("RUB");
        long transaction = newLedgerTransaction("TOP_UP");

        assertThatThrownBy(() -> addEntry(transaction, alice, "10", "USD"))
                .rootCause().hasMessageContaining("does not match account");
    }

    @Test
    void entriesCannotBeUpdatedOrDeleted() {
        long alice = newUserAccount("RUB");
        long gateway = systemAccount("SYSTEM_GATEWAY");
        long transaction = tx().execute(status -> {
            long id = newLedgerTransaction("TOP_UP");
            addEntry(id, gateway, "-5", "RUB");
            addEntry(id, alice, "5", "RUB");
            return id;
        });

        assertThatThrownBy(() -> jdbc.update("update ledger_entries set amount = 6 where transaction_id = ?", transaction))
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from ledger_entries where transaction_id = ?", transaction))
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from ledger_transactions where id = ?", transaction))
                .rootCause().hasMessageContaining("append-only");
    }
}
