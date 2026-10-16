package dev.finflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ConcurrentCashTest extends AbstractPostgresTest {

    @Autowired
    private CashService cash;

    @Autowired
    private AccountService accounts;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void parallelWithdrawalsThroughServiceNeverLoseUpdates() throws Exception {
        UUID userId = users.save(User.register("cash-" + UUID.randomUUID() + "@example.com", "hash", "Test")).getId();
        AccountResponse account = accounts.open(userId, new OpenAccountRequest(OpenableAccountType.USER_WALLET, "RUB"));
        cash.topUp(userId, new CashOperationRequest(account.id(), new BigDecimal("100")));

        int requests = 20;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger insufficient = new AtomicInteger();
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    cash.withdraw(userId, new CashOperationRequest(account.id(), new BigDecimal("10")));
                    return true;
                } catch (InsufficientFundsException e) {
                    insufficient.incrementAndGet();
                    return false;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                succeeded++;
            }
        }
        pool.shutdownNow();

        BigDecimal balance = jdbc.queryForObject("select balance from account_balances where account_id = ?",
                BigDecimal.class, account.id());
        assertThat(succeeded).isEqualTo(10);
        assertThat(insufficient.get()).isEqualTo(10);
        assertThat(balance).isEqualByComparingTo("0");
    }
}
