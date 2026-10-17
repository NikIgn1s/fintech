package dev.finflow.transfer.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.finflow.account.api.AccountResponse;
import dev.finflow.account.api.OpenAccountRequest;
import dev.finflow.account.api.OpenableAccountType;
import dev.finflow.account.service.AccountService;
import dev.finflow.auth.domain.User;
import dev.finflow.auth.repository.UserRepository;
import dev.finflow.ledger.api.CashOperationRequest;
import dev.finflow.ledger.service.CashService;
import dev.finflow.transfer.api.TransferResponse;
import dev.finflow.transfer.service.CreateTransferCommand;
import dev.finflow.transfer.service.TransferService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class FraudFlowFixtures {

    public record Pending(UUID senderId, AccountResponse from, AccountResponse to, TransferResponse transfer) {
    }

    private final UserRepository users;
    private final AccountService accounts;
    private final CashService cash;
    private final TransferService transfers;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public FraudFlowFixtures(UserRepository users, AccountService accounts, CashService cash,
            TransferService transfers, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.users = users;
        this.accounts = accounts;
        this.cash = cash;
        this.transfers = transfers;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Pending pendingTransfer(String amount) {
        UUID sender = users.save(User.register("s-" + UUID.randomUUID() + "@example.com", "hash", "Sender")).getId();
        UUID recipient = users.save(User.register("r-" + UUID.randomUUID() + "@example.com", "hash", "Recipient"))
                .getId();
        AccountResponse from = accounts.open(sender, new OpenAccountRequest(OpenableAccountType.USER_WALLET, "RUB"));
        AccountResponse to = accounts.open(recipient, new OpenAccountRequest(OpenableAccountType.USER_WALLET, "RUB"));
        cash.topUp(sender, new CashOperationRequest(from.id(), new BigDecimal("100000")));
        TransferResponse transfer = transfers.create(sender,
                new CreateTransferCommand(from.id(), to.id(), new BigDecimal(amount), "flow"));
        return new Pending(sender, from, to, transfer);
    }

    public ObjectNode resultEnvelope(UUID eventId, UUID transferId, String decision, String score,
            List<String> reasons) {
        ObjectNode envelope = objectMapper.createObjectNode();
        if (eventId != null) {
            envelope.put("eventId", eventId.toString());
        }
        envelope.put("eventType", "FraudCheckCompleted");
        envelope.put("version", 1);
        envelope.put("occurredAt", Instant.now().toString());
        ObjectNode payload = envelope.putObject("payload");
        if (transferId != null) {
            payload.put("transferId", transferId.toString());
        }
        if (score != null) {
            payload.put("score", new BigDecimal(score));
        }
        if (decision != null) {
            payload.put("decision", decision);
        }
        payload.set("reasons", objectMapper.valueToTree(reasons));
        payload.put("modelVersion", "test-model-1");
        return envelope;
    }

    public BigDecimal balance(long accountId) {
        return jdbc.queryForObject("select balance from account_balances where account_id = ?", BigDecimal.class,
                accountId);
    }

    public String status(UUID transferId) {
        return jdbc.queryForObject("select status from transfers where id = ?", String.class, transferId);
    }

    public int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    public int settleCount(UUID transferId) {
        return count("select count(*) from ledger_transactions where type = 'TRANSFER_SETTLE' and reference_id = ?",
                transferId);
    }

    public int fraudCheckCount(UUID transferId) {
        return count("select count(*) from fraud_checks where transfer_id = ?", transferId);
    }

    public int statusChangedEvents(UUID transferId) {
        return count("select count(*) from outbox_events where topic = 'transfers.status-changed' "
                + "and aggregate_id = ?", transferId.toString());
    }
}
