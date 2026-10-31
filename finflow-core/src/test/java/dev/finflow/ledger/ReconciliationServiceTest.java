package dev.finflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finflow.common.money.Money;
import dev.finflow.ledger.domain.BalanceMismatch;
import dev.finflow.ledger.api.LedgerTransactionType;
import dev.finflow.ledger.api.Posting;
import dev.finflow.ledger.domain.ReconciliationReport;
import dev.finflow.ledger.service.ReconciliationService;
import dev.finflow.support.AbstractPostgresTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class ReconciliationServiceTest extends AbstractPostgresTest {

    @Autowired
    private ReconciliationService reconciliation;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry registry;

    private long fundedAccount(String amount) {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into users (id, email, password_hash, full_name) values (?, ?, 'hash', 'Test')",
                userId, userId + "@example.com");
        long account = jdbc.queryForObject(
                "insert into accounts (user_id, type, currency) values (?, 'USER_WALLET', 'RUB') returning id",
                Long.class, userId);
        long gateway = jdbc.queryForObject(
                "select id from accounts where type = 'SYSTEM_GATEWAY' and currency = 'RUB'", Long.class);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> ledger.post(
                LedgerTransactionType.TOP_UP, UUID.randomUUID(), List.of(
                        new Posting(gateway, Money.of("-" + amount, "RUB")),
                        new Posting(account, Money.of(amount, "RUB")))));
        return account;
    }

    private Optional<BalanceMismatch> mismatchOf(ReconciliationReport report, long account) {
        return report.mismatches().stream().filter(m -> m.accountId() == account).findFirst();
    }

    private double gaugeValue() {
        return registry.get("ledger_reconciliation_mismatch").gauge().value();
    }

    @Test
    void consistentAccountIsNotReported() {
        long account = fundedAccount("100");

        ReconciliationReport report = reconciliation.run();

        assertThat(mismatchOf(report, account)).isEmpty();
        assertThat(report.mismatchCount()).isEqualTo(report.mismatches().size());
        assertThat(gaugeValue()).isEqualTo(report.mismatchCount());
    }

    @Test
    void detectsTamperedBalanceAndRecoversAfterFix() {
        long account = fundedAccount("100");
        jdbc.update("update account_balances set balance = balance + 7 where account_id = ?", account);

        try {
            ReconciliationReport report = reconciliation.run();

            BalanceMismatch mismatch = mismatchOf(report, account).orElseThrow();
            assertThat(mismatch.balance()).isEqualByComparingTo("107");
            assertThat(mismatch.ledgerSum()).isEqualByComparingTo("100");
            assertThat(gaugeValue()).isEqualTo(report.mismatchCount());
        } finally {
            jdbc.update("update account_balances set balance = balance - 7 where account_id = ?", account);
        }

        assertThat(mismatchOf(reconciliation.run(), account)).isEmpty();
    }

    @Test
    void gaugeGrowsByOneWhenOneMoreAccountDiverges() {
        long account = fundedAccount("20");
        double baseline = reconciliation.run().mismatchCount();
        jdbc.update("update account_balances set balance = balance + 1 where account_id = ?", account);

        try {
            reconciliation.run();

            assertThat(gaugeValue()).isEqualTo(baseline + 1);
        } finally {
            jdbc.update("update account_balances set balance = balance - 1 where account_id = ?", account);
        }
    }

    @Test
    void storesRunAndReturnsItAsLatest() {
        long account = fundedAccount("5");
        jdbc.update("update account_balances set balance = balance + 1 where account_id = ?", account);

        try {
            ReconciliationReport run = reconciliation.run();
            ReconciliationReport latest = reconciliation.latest().orElseThrow();

            assertThat(latest.id()).isEqualTo(run.id());
            assertThat(latest.mismatchCount()).isEqualTo(run.mismatchCount());
            assertThat(mismatchOf(latest, account).orElseThrow().balance()).isEqualByComparingTo("6");
            assertThat(latest.finishedAt()).isAfterOrEqualTo(latest.startedAt());
        } finally {
            jdbc.update("update account_balances set balance = balance - 1 where account_id = ?", account);
        }
    }
}
