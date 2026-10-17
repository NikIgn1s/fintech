package dev.finflow.transfer;

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
import dev.finflow.transfer.service.CreateTransferCommand;
import dev.finflow.transfer.service.TransferService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class TransferConcurrencyTest extends AbstractPostgresTest {

    private static final int THREADS = 32;
    private static final long TIMEOUT_SECONDS = 180;

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

    private record Party(UUID userId, long accountId) {
    }

    private record Outcome(AtomicInteger succeeded, AtomicInteger insufficient, List<Throwable> unexpected) {
    }

    private Party fundedParty(String amount) {
        UUID userId = users.save(User.register("p-" + UUID.randomUUID() + "@example.com", "hash", "Party")).getId();
        AccountResponse account = accounts.open(userId, new OpenAccountRequest(OpenableAccountType.USER_WALLET, "RUB"));
        cash.topUp(userId, new CashOperationRequest(account.id(), new BigDecimal(amount)));
        return new Party(userId, account.id());
    }

    private BigDecimal suspenseBalance() {
        return jdbc.queryForObject("""
                select b.balance from account_balances b join accounts a on a.id = b.account_id
                where a.type = 'SYSTEM_SUSPENSE' and a.currency = 'RUB'
                """, BigDecimal.class);
    }

    private BigDecimal totalBalance(List<Party> parties) {
        return jdbc.queryForObject("select coalesce(sum(balance), 0) from account_balances where account_id in ("
                + ids(parties) + ")", BigDecimal.class);
    }

    private String ids(List<Party> parties) {
        return parties.stream().map(party -> String.valueOf(party.accountId())).collect(Collectors.joining(","));
    }

    private Outcome runConcurrently(List<Callable<Void>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        Outcome outcome = new Outcome(new AtomicInteger(), new AtomicInteger(), new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    task.call();
                    outcome.succeeded().incrementAndGet();
                } catch (InsufficientFundsException e) {
                    outcome.insufficient().incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            try {
                future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                outcome.unexpected().add(e);
            }
        }
        pool.shutdownNow();
        return outcome;
    }

    private Callable<Void> transfer(Party from, Party to, String amount) {
        return () -> {
            transfers.create(from.userId(), new CreateTransferCommand(from.accountId(), to.accountId(),
                    new BigDecimal(amount), "load"));
            return null;
        };
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void oppositeTransfersDoNotDeadlockAndConserveMoney() throws Exception {
        Party alice = fundedParty("100000");
        Party bob = fundedParty("100000");
        BigDecimal suspenseBefore = suspenseBalance();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(i % 2 == 0 ? transfer(alice, bob, "10") : transfer(bob, alice, "10"));
        }
        Outcome outcome = runConcurrently(tasks);

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(outcome.succeeded().get()).isEqualTo(100);
        BigDecimal reserved = suspenseBalance().subtract(suspenseBefore);
        assertThat(reserved).isEqualByComparingTo("1000");
        assertThat(totalBalance(List.of(alice, bob)).add(reserved)).isEqualByComparingTo("200000");
        assertThat(count("select count(*) from transfers where from_account_id in (" + ids(List.of(alice, bob)) + ")"))
                .isEqualTo(100);
    }

    @Test
    void randomTransfersAmongManyUsersConserveMoneyAndNeverOverdraw() throws Exception {
        List<Party> parties = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            parties.add(fundedParty("5000"));
        }
        BigDecimal suspenseBefore = suspenseBalance();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            tasks.add(() -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                int from = random.nextInt(parties.size());
                int to = (from + 1 + random.nextInt(parties.size() - 1)) % parties.size();
                return transfer(parties.get(from), parties.get(to), String.valueOf(1 + random.nextInt(1500))).call();
            });
        }
        Outcome outcome = runConcurrently(tasks);

        assertThat(outcome.unexpected()).isEmpty();
        BigDecimal reserved = suspenseBalance().subtract(suspenseBefore);
        assertThat(totalBalance(parties).add(reserved)).isEqualByComparingTo("50000");
        assertThat(jdbc.queryForList("select balance from account_balances where account_id in (" + ids(parties) + ")",
                BigDecimal.class)).allMatch(balance -> balance.signum() >= 0);
        assertThat(outcome.succeeded().get() + outcome.insufficient().get()).isEqualTo(400);
        int created = count("select count(*) from transfers where from_account_id in (" + ids(parties) + ")");
        assertThat(created).isEqualTo(outcome.succeeded().get());
        int reserves = count("""
                select count(*) from ledger_transactions lt join transfers t on t.id = lt.reference_id
                where lt.type = 'TRANSFER_RESERVE' and t.from_account_id in (%s)
                """.formatted(ids(parties)));
        assertThat(reserves).isEqualTo(created);
    }

    @Test
    void parallelTransfersFromOneAccountNeverOverdrawIt() throws Exception {
        Party sender = fundedParty("100");
        Party recipient = fundedParty("0.01");

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(transfer(sender, recipient, "10"));
        }
        Outcome outcome = runConcurrently(tasks);

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(outcome.succeeded().get()).isEqualTo(10);
        assertThat(outcome.insufficient().get()).isEqualTo(10);
        BigDecimal balance = jdbc.queryForObject("select balance from account_balances where account_id = ?",
                BigDecimal.class, sender.accountId());
        assertThat(balance).isEqualByComparingTo("0");
    }
}
