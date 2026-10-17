package dev.finflow.transfer.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.account.api.AccountResponse;
import dev.finflow.account.api.OpenAccountRequest;
import dev.finflow.account.api.OpenableAccountType;
import dev.finflow.account.service.AccountService;
import dev.finflow.auth.domain.User;
import dev.finflow.auth.repository.UserRepository;
import dev.finflow.ledger.api.CashOperationRequest;
import dev.finflow.ledger.domain.InsufficientFundsException;
import dev.finflow.ledger.service.CashService;
import dev.finflow.support.AbstractPostgresTest;
import dev.finflow.transfer.api.TransferResponse;
import dev.finflow.transfer.service.CreateTransferCommand;
import dev.finflow.transfer.service.TransferService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class TransferCheckEventTest extends AbstractPostgresTest {

    @Autowired
    private TransferService transfers;

    @Autowired
    private AccountService accounts;

    @Autowired
    private CashService cash;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID newUser() {
        return users.save(User.register("u-" + UUID.randomUUID() + "@example.com", "hash", "Test")).getId();
    }

    private AccountResponse account(UUID userId) {
        return accounts.open(userId, new OpenAccountRequest(OpenableAccountType.USER_WALLET, "RUB"));
    }

    private AccountResponse fundedAccount(UUID userId, String amount) {
        AccountResponse account = account(userId);
        cash.topUp(userId, new CashOperationRequest(account.id(), new BigDecimal(amount)));
        return account;
    }

    private TransferResponse send(UUID from, AccountResponse source, AccountResponse target, String amount) {
        return transfers.create(from, new CreateTransferCommand(source.id(), target.id(), new BigDecimal(amount), "t"));
    }

    private Map<String, Object> outboxRow(UUID transferId) {
        return jdbc.queryForMap("select * from outbox_events where aggregate_id = ? and topic = 'transfers.check'",
                transferId.toString());
    }

    private JsonNode envelope(UUID transferId) throws Exception {
        return objectMapper.readTree(outboxRow(transferId).get("payload").toString());
    }

    @Test
    void publishesCheckRequestInTheSameTransactionAsTheTransfer() throws Exception {
        UUID alice = newUser();
        UUID bob = newUser();
        AccountResponse from = fundedAccount(alice, "1000");
        AccountResponse to = account(bob);

        TransferResponse transfer = send(alice, from, to, "250.50");

        Map<String, Object> row = outboxRow(transfer.id());
        assertThat(row.get("aggregate_type")).isEqualTo("transfer");
        assertThat(row.get("event_type")).isEqualTo("TransferCheckRequested");
        assertThat(row.get("published_at")).isNull();
        JsonNode envelope = envelope(transfer.id());
        assertThat(envelope.get("eventType").asText()).isEqualTo("TransferCheckRequested");
        assertThat(envelope.get("version").asInt()).isEqualTo(1);
    }

    @Test
    void firstTransferCarriesBaselineFeatures() throws Exception {
        UUID alice = newUser();
        UUID bob = newUser();
        AccountResponse from = fundedAccount(alice, "1000");
        AccountResponse to = account(bob);

        TransferResponse transfer = send(alice, from, to, "250.50");
        JsonNode payload = envelope(transfer.id()).get("payload");

        assertThat(payload.get("transferId").asText()).isEqualTo(transfer.id().toString());
        assertThat(payload.get("fromUserId").asText()).isEqualTo(alice.toString());
        assertThat(payload.get("toUserId").asText()).isEqualTo(bob.toString());
        assertThat(payload.get("amount").asText()).isEqualTo("250.5000");
        assertThat(payload.get("currency").asText()).isEqualTo("RUB");
        assertThat(payload.get("fromAccountAgeDays").asLong()).isZero();
        assertThat(payload.get("recipientAccountAgeDays").asLong()).isZero();
        assertThat(payload.get("isFirstTransferToRecipient").asBoolean()).isTrue();
        assertThat(payload.get("transfersLast1h").asLong()).isZero();
        assertThat(payload.get("transfersLast24h").asLong()).isZero();
        assertThat(payload.get("amountLast24h").asText()).isEqualTo("0.0000");
        assertThat(payload.get("avgTransferAmount30d").asText()).isEqualTo("0.0000");
        assertThat(payload.get("recipientIncomingCount24h").asLong()).isZero();
        Instant createdAt = Instant.parse(payload.get("createdAt").asText());
        assertThat(payload.get("hourOfDay").asInt()).isEqualTo(createdAt.atOffset(ZoneOffset.UTC).getHour());
    }

    @Test
    void laterTransfersSeeSendersHistory() throws Exception {
        UUID alice = newUser();
        AccountResponse from = fundedAccount(alice, "10000");
        AccountResponse to = account(newUser());
        send(alice, from, to, "100");
        send(alice, from, to, "300");

        TransferResponse third = send(alice, from, to, "50");
        JsonNode payload = envelope(third.id()).get("payload");

        assertThat(payload.get("transfersLast1h").asLong()).isEqualTo(2);
        assertThat(payload.get("transfersLast24h").asLong()).isEqualTo(2);
        assertThat(payload.get("amountLast24h").asText()).isEqualTo("400.0000");
        assertThat(payload.get("avgTransferAmount30d").asText()).isEqualTo("200.0000");
        assertThat(payload.get("recipientIncomingCount24h").asLong()).isEqualTo(2);
    }

    @Test
    void completedTransferToTheSameRecipientMakesItFamiliar() throws Exception {
        UUID alice = newUser();
        AccountResponse from = fundedAccount(alice, "1000");
        AccountResponse to = account(newUser());
        TransferResponse first = send(alice, from, to, "100");

        jdbc.update("update transfers set status = 'COMPLETED' where id = ?", first.id());
        TransferResponse second = send(alice, from, to, "100");

        assertThat(envelope(first.id()).get("payload").get("isFirstTransferToRecipient").asBoolean()).isTrue();
        assertThat(envelope(second.id()).get("payload").get("isFirstTransferToRecipient").asBoolean()).isFalse();
    }

    @Test
    void failedTransfersDoNotCountAsHistory() throws Exception {
        UUID alice = newUser();
        AccountResponse from = fundedAccount(alice, "1000");
        AccountResponse to = account(newUser());
        TransferResponse first = send(alice, from, to, "100");
        jdbc.update("update transfers set status = 'REJECTED' where id = ?", first.id());

        TransferResponse second = send(alice, from, to, "100");

        JsonNode payload = envelope(second.id()).get("payload");
        assertThat(payload.get("transfersLast24h").asLong()).isZero();
        assertThat(payload.get("amountLast24h").asText()).isEqualTo("0.0000");
    }

    @Test
    void recipientIncomingCountIncludesOtherSenders() throws Exception {
        UUID alice = newUser();
        UUID carol = newUser();
        AccountResponse aliceAccount = fundedAccount(alice, "1000");
        AccountResponse carolAccount = fundedAccount(carol, "1000");
        AccountResponse recipient = account(newUser());
        send(alice, aliceAccount, recipient, "10");

        TransferResponse fromCarol = send(carol, carolAccount, recipient, "10");

        assertThat(envelope(fromCarol.id()).get("payload").get("recipientIncomingCount24h").asLong()).isEqualTo(1);
    }

    @Test
    void failedTransferLeavesNoEvent() {
        UUID alice = newUser();
        AccountResponse from = fundedAccount(alice, "50");
        AccountResponse to = account(newUser());
        Integer before = jdbc.queryForObject("select count(*) from outbox_events where topic = 'transfers.check'",
                Integer.class);

        assertThatThrownBy(() -> send(alice, from, to, "50.01")).isInstanceOf(InsufficientFundsException.class);

        Integer after = jdbc.queryForObject("select count(*) from outbox_events where topic = 'transfers.check'",
                Integer.class);
        assertThat(after).isEqualTo(before);
    }
}
