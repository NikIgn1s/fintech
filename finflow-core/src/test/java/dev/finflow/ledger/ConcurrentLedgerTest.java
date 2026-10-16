package dev.finflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finflow.common.money.Money;
import dev.finflow.ledger.domain.InsufficientFundsException;
import dev.finflow.ledger.domain.LedgerTransactionType;
import dev.finflow.ledger.domain.Posting;
import dev.finflow.support.AbstractPostgresTest;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class ConcurrentLedgerTest extends AbstractPostgresTest {

    private static final int THREADS = 32;
    private static final long TIMEOUT_SECONDS = 120;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private long gateway() {
        return jdbc.queryForObject(
                "select id from accounts where type = 'SYSTEM_GATEWAY' and currency = 'RUB'", Long.class);
    }

    private long fundedAccount(String amount) {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into users (id, email, password_hash, full_name) values (?, ?, 'hash', 'Test')",
                userId, userId + "@example.com");
        long account = jdbc.queryForObject(
                "insert into accounts (user_id, type, currency) values (?, 'USER_WALLET', 'RUB') returning id",
                Long.class, userId);
        long gateway = gateway();
        tx().executeWithoutResult(status -> ledger.post(LedgerTransactionType.TOP_UP, UUID.randomUUID(), List.of(
                new Posting(gateway, Money.of("-" + amount, "RUB")),
                new Posting(account, Money.of(amount, "RUB")))));
        return account;
    }

    private void transfer(long from, long to, long amount) {
        tx().executeWithoutResult(status -> ledger.post(LedgerTransactionType.TRANSFER_RESERVE, UUID.randomUUID(),
                List.of(new Posting(from, Money.of(String.valueOf(-amount), "RUB")),
                        new Posting(to, Money.of(String.valueOf(amount), "RUB")))));
    }

    private Outcome runConcurrently(List<Callable<Void>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger insufficient = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return task.call();
                } catch (InsufficientFundsException e) {
                    insufficient.incrementAndGet();
                    return null;
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            try {
                future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                unexpected.add(e);
            }
        }
        pool.shutdownNow();
        return new Outcome(insufficient.get(), unexpected);
    }

    private BigDecimal totalBalance(List<Long> accounts) {
        String ids = accounts.stream().map(String::valueOf).collect(Collectors.joining(","));
        return jdbc.queryForObject("select coalesce(sum(balance), 0) from account_balances where account_id in ("
                + ids + ")", BigDecimal.class);
    }

    private List<BigDecimal> balances(List<Long> accounts) {
        String ids = accounts.stream().map(String::valueOf).collect(Collectors.joining(","));
        return jdbc.queryForList("select balance from account_balances where account_id in (" + ids + ")",
                BigDecimal.class);
    }

    private List<Long> accountsWithMismatch(List<Long> accounts) {
        String ids = accounts.stream().map(String::valueOf).collect(Collectors.joining(","));
        return jdbc.queryForList("""
                select b.account_id
                from account_balances b
                left join ledger_entries e on e.account_id = b.account_id
                where b.account_id in (%s)
                group by b.account_id, b.balance
                having b.balance <> coalesce(sum(e.amount), 0)
                """.formatted(ids), Long.class);
    }

    private record Outcome(int insufficientFunds, List<Throwable> unexpected) {
    }

    @Test
    void randomTransfersPreserveTotalMoneyAndNeverGoNegative() throws Exception {
        List<Long> accounts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            accounts.add(fundedAccount("10000"));
        }
        BigDecimal initialTotal = totalBalance(accounts);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            tasks.add(() -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                int from = random.nextInt(accounts.size());
                int to = (from + 1 + random.nextInt(accounts.size() - 1)) % accounts.size();
                transfer(accounts.get(from), accounts.get(to), 1 + random.nextInt(2500));
                return null;
            });
        }

        Outcome outcome = runConcurrently(tasks);

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(totalBalance(accounts)).isEqualByComparingTo(initialTotal);
        assertThat(balances(accounts)).allMatch(balance -> balance.signum() >= 0);
        assertThat(accountsWithMismatch(accounts)).isEmpty();
    }

    @Test
    void oppositeTransfersBetweenTwoAccountsDoNotDeadlock() throws Exception {
        long alice = fundedAccount("100000");
        long bob = fundedAccount("100000");
        List<Long> accounts = List.of(alice, bob);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            boolean forward = i % 2 == 0;
            tasks.add(() -> {
                transfer(forward ? alice : bob, forward ? bob : alice, 10);
                return null;
            });
        }

        Outcome outcome = runConcurrently(tasks);

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(outcome.insufficientFunds()).isZero();
        assertThat(balances(accounts)).allSatisfy(balance -> assertThat(balance).isEqualByComparingTo("100000"));
        assertThat(accountsWithMismatch(accounts)).isEmpty();
    }

    @Test
    void parallelWithdrawalsNeverOverdrawTheAccount() throws Exception {
        long account = fundedAccount("1000");
        long gateway = gateway();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(() -> {
                transfer(account, gateway, 30);
                return null;
            });
        }

        Outcome outcome = runConcurrently(tasks);

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(outcome.insufficientFunds()).isEqualTo(100 - 33);
        assertThat(balances(List.of(account))).singleElement()
                .satisfies(balance -> assertThat(balance).isEqualByComparingTo("10"));
        assertThat(accountsWithMismatch(List.of(account))).isEmpty();
    }
}
