package dev.finflow.support;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public abstract class AbstractLedgerSchemaTest extends AbstractPostgresTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    protected TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    protected long newUserAccount(String currency) {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into users (id, email, password_hash, full_name) values (?, ?, 'hash', 'Test')",
                userId, userId + "@example.com");
        return jdbc.queryForObject(
                "insert into accounts (user_id, type, currency) values (?, 'USER_WALLET', ?) returning id",
                Long.class, userId, currency);
    }

    protected long systemAccount(String type) {
        return jdbc.queryForObject("select id from accounts where type = ? and currency = 'RUB' and user_id is null", Long.class, type);
    }

    protected long newLedgerTransaction(String type) {
        return jdbc.queryForObject(
                "insert into ledger_transactions (type, reference_id) values (?, ?) returning id",
                Long.class, type, UUID.randomUUID());
    }

    protected void addEntry(long transactionId, long accountId, String amount, String currency) {
        jdbc.update("insert into ledger_entries (transaction_id, account_id, amount, currency) "
                + "values (?, ?, ?::numeric, ?)", transactionId, accountId, amount, currency);
    }
}
