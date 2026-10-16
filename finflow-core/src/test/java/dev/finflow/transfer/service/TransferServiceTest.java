package dev.finflow.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finflow.account.api.AccountResponse;
import dev.finflow.account.api.OpenAccountRequest;
import dev.finflow.account.api.OpenableAccountType;
import dev.finflow.account.service.AccountService;
import dev.finflow.auth.domain.User;
import dev.finflow.auth.repository.UserRepository;
import dev.finflow.common.error.BusinessRuleException;
import dev.finflow.common.error.NotFoundException;
import dev.finflow.ledger.api.CashOperationRequest;
import dev.finflow.ledger.domain.InsufficientFundsException;
import dev.finflow.ledger.service.CashService;
import dev.finflow.support.AbstractPostgresTest;
import dev.finflow.transfer.api.TransferResponse;
import dev.finflow.transfer.domain.TransferStatus;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class TransferServiceTest extends AbstractPostgresTest {

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

    private UUID newUser() {
        return users.save(User.register("user-" + UUID.randomUUID() + "@example.com", "hash", "Test")).getId();
    }

    private AccountResponse openAccount(UUID userId, String currency) {
        return accounts.open(userId, new OpenAccountRequest(OpenableAccountType.USER_WALLET, currency));
    }

    private AccountResponse fundedAccount(UUID userId, String amount) {
        AccountResponse account = openAccount(userId, "RUB");
        cash.topUp(userId, new CashOperationRequest(account.id(), new BigDecimal(amount)));
        return account;
    }

    private BigDecimal balance(long accountId) {
        return jdbc.queryForObject("select balance from account_balances where account_id = ?", BigDecimal.class,
                accountId);
    }

    private BigDecimal suspenseBalance(String currency) {
        return jdbc.queryForObject("""
                select b.balance from account_balances b join accounts a on a.id = b.account_id
                where a.type = 'SYSTEM_SUSPENSE' and a.currency = ?
                """, BigDecimal.class, currency);
    }

    private CreateTransferCommand command(long from, long to, String amount) {
        return new CreateTransferCommand(from, to, new BigDecimal(amount), "test");
    }

    @Test
    void createsPendingTransferAndReservesMoneyInSuspense() {
        UUID alice = newUser();
        UUID bob = newUser();
        AccountResponse from = fundedAccount(alice, "1000");
        AccountResponse to = openAccount(bob, "RUB");
        BigDecimal suspenseBefore = suspenseBalance("RUB");

        TransferResponse transfer = transfers.create(alice, command(from.id(), to.id(), "300.50"));

        assertThat(transfer.status()).isEqualTo(TransferStatus.PENDING_CHECK);
        assertThat(transfer.amount()).isEqualByComparingTo("300.50");
        assertThat(transfer.currency()).isEqualTo("RUB");
        assertThat(balance(from.id())).isEqualByComparingTo("699.50");
        assertThat(balance(to.id())).isEqualByComparingTo("0");
        assertThat(suspenseBalance("RUB")).isEqualByComparingTo(suspenseBefore.add(new BigDecimal("300.50")));
        Integer reserves = jdbc.queryForObject(
                "select count(*) from ledger_transactions where type = 'TRANSFER_RESERVE' and reference_id = ?",
                Integer.class, transfer.id());
        assertThat(reserves).isEqualTo(1);
    }

    @Test
    void allowsTransferBetweenOwnAccounts() {
        UUID alice = newUser();
        AccountResponse wallet = fundedAccount(alice, "100");
        AccountResponse savings = openAccount(alice, "RUB");

        TransferResponse transfer = transfers.create(alice, command(wallet.id(), savings.id(), "40"));

        assertThat(transfer.status()).isEqualTo(TransferStatus.PENDING_CHECK);
        assertThat(balance(wallet.id())).isEqualByComparingTo("60");
    }

    @Test
    void insufficientFundsLeavesNoTraceAtAll() {
        UUID alice = newUser();
        UUID bob = newUser();
        AccountResponse from = fundedAccount(alice, "50");
        AccountResponse to = openAccount(bob, "RUB");
        BigDecimal suspenseBefore = suspenseBalance("RUB");

        assertThatThrownBy(() -> transfers.create(alice, command(from.id(), to.id(), "50.01")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balance(from.id())).isEqualByComparingTo("50");
        assertThat(suspenseBalance("RUB")).isEqualByComparingTo(suspenseBefore);
        Integer count = jdbc.queryForObject("select count(*) from transfers where from_account_id = ?",
                Integer.class, from.id());
        assertThat(count).isZero();
    }

    @Test
    void cannotSpendFromSomeoneElsesAccount() {
        UUID alice = newUser();
        UUID bob = newUser();
        UUID mallory = newUser();
        AccountResponse aliceAccount = fundedAccount(alice, "100");
        AccountResponse bobAccount = openAccount(bob, "RUB");

        assertThatThrownBy(() -> transfers.create(mallory, command(aliceAccount.id(), bobAccount.id(), "10")))
                .isInstanceOf(NotFoundException.class);
        assertThat(balance(aliceAccount.id())).isEqualByComparingTo("100");
    }

    @Test
    void rejectsUnknownAndSystemRecipients() {
        UUID alice = newUser();
        AccountResponse from = fundedAccount(alice, "100");
        long gateway = jdbc.queryForObject(
                "select id from accounts where type = 'SYSTEM_GATEWAY' and currency = 'RUB'", Long.class);

        assertThatThrownBy(() -> transfers.create(alice, command(from.id(), 987654321L, "10")))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> transfers.create(alice, command(from.id(), gateway, "10")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void rejectsTransferToTheSameAccount() {
        UUID alice = newUser();
        AccountResponse from = fundedAccount(alice, "100");

        assertThatThrownBy(() -> transfers.create(alice, command(from.id(), from.id(), "10")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("differ");
    }

    @Test
    void rejectsDifferentCurrencies() {
        UUID alice = newUser();
        UUID bob = newUser();
        AccountResponse from = fundedAccount(alice, "100");
        AccountResponse usd = openAccount(bob, "USD");

        assertThatThrownBy(() -> transfers.create(alice, command(from.id(), usd.id(), "10")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    void rejectsClosedAccounts() {
        UUID alice = newUser();
        UUID bob = newUser();
        AccountResponse from = fundedAccount(alice, "100");
        AccountResponse to = openAccount(bob, "RUB");

        jdbc.update("update accounts set status = 'CLOSED' where id = ?", to.id());
        assertThatThrownBy(() -> transfers.create(alice, command(from.id(), to.id(), "10")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Recipient");

        jdbc.update("update accounts set status = 'ACTIVE' where id = ?", to.id());
        jdbc.update("update accounts set status = 'CLOSED' where id = ?", from.id());
        assertThatThrownBy(() -> transfers.create(alice, command(from.id(), to.id(), "10")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Source");
    }
}
