package dev.finflow.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finflow.support.AbstractLedgerSchemaTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class TransfersSchemaTest extends AbstractLedgerSchemaTest {

    private UUID userId() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, email, password_hash, full_name) values (?, ?, 'hash', 'Test')",
                id, id + "@example.com");
        return id;
    }

    private UUID insertTransfer(UUID initiator, long from, long to, String amount, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into transfers (id, from_account_id, to_account_id, amount, currency, status, initiated_by) "
                + "values (?, ?, ?, ?::numeric, 'RUB', ?, ?)", id, from, to, amount, status, initiator);
        return id;
    }

    @Test
    void storesTransferWithDefaults() {
        UUID initiator = userId();
        long from = newUserAccount("RUB");
        long to = newUserAccount("RUB");

        UUID id = insertTransfer(initiator, from, to, "100.5", "PENDING_CHECK");

        Long version = jdbc.queryForObject("select version from transfers where id = ?", Long.class, id);
        assertThat(version).isZero();
    }

    @Test
    void rejectsTransferToTheSameAccount() {
        UUID initiator = userId();
        long account = newUserAccount("RUB");

        assertThatThrownBy(() -> insertTransfer(initiator, account, account, "10", "PENDING_CHECK"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNonPositiveAmount() {
        UUID initiator = userId();
        long from = newUserAccount("RUB");
        long to = newUserAccount("RUB");

        assertThatThrownBy(() -> insertTransfer(initiator, from, to, "0", "PENDING_CHECK"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertTransfer(initiator, from, to, "-1", "PENDING_CHECK"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnknownStatus() {
        UUID initiator = userId();
        long from = newUserAccount("RUB");
        long to = newUserAccount("RUB");

        assertThatThrownBy(() -> insertTransfer(initiator, from, to, "10", "DONE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void fraudCheckRequiresExistingTransferAndKnownValues() {
        UUID initiator = userId();
        long from = newUserAccount("RUB");
        long to = newUserAccount("RUB");
        UUID transfer = insertTransfer(initiator, from, to, "10", "PENDING_CHECK");

        jdbc.update("insert into fraud_checks (transfer_id, score, decision, source) values (?, 0.1234, 'APPROVE', 'ML')",
                transfer);

        assertThatThrownBy(() -> jdbc.update(
                "insert into fraud_checks (transfer_id, decision, source) values (?, 'APPROVE', 'ML')",
                UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into fraud_checks (transfer_id, decision, source) values (?, 'MAYBE', 'ML')", transfer))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into fraud_checks (transfer_id, decision, source) values (?, 'HOLD', 'MANUAL')", transfer))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void idempotencyKeyIsUniquePerUser() {
        UUID alice = userId();
        UUID bob = userId();
        String key = UUID.randomUUID().toString();
        String insert = "insert into idempotency_keys (user_id, key, request_hash) values (?, ?, 'abc')";

        jdbc.update(insert, alice, key);
        jdbc.update(insert, bob, key);

        assertThatThrownBy(() -> jdbc.update(insert, alice, key)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
