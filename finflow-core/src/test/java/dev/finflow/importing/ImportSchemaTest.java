package dev.finflow.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finflow.support.AbstractLedgerSchemaTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ImportSchemaTest extends AbstractLedgerSchemaTest {

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, email, password_hash, full_name) values (?, ?, 'hash', 'Test')",
                id, id + "@example.com");
        return id;
    }

    private UUID statementImport(UUID user, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into statement_imports (id, user_id, bank_format, file_key, status) "
                + "values (?, ?, 'SBER', 'k', ?)", id, user, status);
        return id;
    }

    private void transaction(UUID user, UUID importId, String hash, String source) {
        jdbc.update("insert into bank_transactions (user_id, import_id, operation_date, amount, currency, "
                + "raw_description, category_source, dedup_hash) values (?, ?, current_date, -10, 'RUB', 'x', ?, ?)",
                user, importId, source, hash);
    }

    @Test
    void seedsEveryCategoryTheMlServiceCanReturn() {
        List<String> codes = jdbc.queryForList("select code from categories", String.class);

        assertThat(codes).contains("GROCERIES", "CAFE", "RESTAURANTS", "TRANSPORT", "TAXI", "FUEL", "PHARMACY",
                "HEALTH", "ENTERTAINMENT", "CLOTHING", "ELECTRONICS", "UTILITIES", "TELECOM", "EDUCATION",
                "TRAVEL", "SUBSCRIPTIONS", "OTHER", "INCOME");
        assertThat(jdbc.queryForObject("select name_ru from categories where code = 'GROCERIES'", String.class))
                .isEqualTo("Продукты");
    }

    @Test
    void sameStatementLineCannotBeImportedTwiceForOneUser() {
        UUID alice = user();
        UUID bob = user();
        UUID first = statementImport(alice, "DONE");
        UUID second = statementImport(alice, "DONE");

        transaction(alice, first, "hash-1", "NONE");
        transaction(bob, null, "hash-1", "NONE");

        assertThatThrownBy(() -> transaction(alice, second, "hash-1", "NONE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnknownImportStatusAndCategorySource() {
        UUID alice = user();

        assertThatThrownBy(() -> statementImport(alice, "WAITING")).isInstanceOf(DataIntegrityViolationException.class);
        UUID importId = statementImport(alice, "UPLOADED");
        assertThatThrownBy(() -> transaction(alice, importId, "h", "MAGIC"))
                .isInstanceOf(DataIntegrityViolationException.class);
        transaction(alice, importId, "h-ok", "MCC");
    }

    @Test
    void importErrorsAreRemovedWithTheirImport() {
        UUID alice = user();
        UUID importId = statementImport(alice, "FAILED");
        jdbc.update("insert into import_errors (import_id, line_number, reason, raw_line) values (?, 7, 'bad date', 'x;y')",
                importId);

        jdbc.update("delete from statement_imports where id = ?", importId);

        Integer left = jdbc.queryForObject("select count(*) from import_errors where import_id = ?", Integer.class,
                importId);
        assertThat(left).isZero();
    }
}
