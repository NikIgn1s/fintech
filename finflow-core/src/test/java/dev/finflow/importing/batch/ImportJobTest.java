package dev.finflow.importing.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import dev.finflow.importing.storage.StatementStorage;
import dev.finflow.support.AbstractPostgresTest;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ImportJobTest extends AbstractPostgresTest {

    private static final Charset WINDOWS_1251 = Charset.forName("windows-1251");

    @MockBean
    private StatementStorage storage;

    @Autowired
    private ImportJobRunner runner;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, email, password_hash, full_name) values (?, ?, 'hash', 'Test')",
                id, id + "@example.com");
        return id;
    }

    private UUID newImport(UUID user, String format, byte[] content) {
        UUID id = UUID.randomUUID();
        String key = "statements/" + user + "/" + id + "/file.csv";
        jdbc.update("insert into statement_imports (id, user_id, bank_format, file_key, status) "
                + "values (?, ?, ?, ?, 'UPLOADED')", id, user, format, key);
        when(storage.open(key)).thenAnswer(call -> new ByteArrayInputStream(content));
        return id;
    }

    private Map<String, Object> importRow(UUID importId) {
        return jdbc.queryForMap("select * from statement_imports where id = ?", importId);
    }

    private static byte[] tinkoff(String... lines) {
        return ("date,description,amount,currency,mcc\n" + String.join("\n", lines) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void importsGoodRowsReportsBadOnesAndPublishesEvent() throws Exception {
        UUID user = newUser();
        UUID importId = newImport(user, "TINKOFF", tinkoff(
                "2026-10-14,PYATEROCHKA 1234 MOSCOW RUS,-540.20,RUB,5411",
                "2026-10-14,YANDEX TAXI*SPB,-312.50,RUB,4121",
                "bad-date,Shop,-1,RUB,",
                "",
                "2026-10-16,SALARY,85000,RUB,"));

        JobExecution execution = runner.run(importId);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> row = importRow(importId);
        assertThat(row.get("status")).isEqualTo("DONE");
        assertThat(row.get("rows_imported")).isEqualTo(3);
        assertThat(row.get("rows_failed")).isEqualTo(1);
        assertThat(row.get("rows_skipped")).isEqualTo(0);
        assertThat(row.get("rows_total")).isEqualTo(4);
        assertThat(row.get("finished_at")).isNotNull();
        assertThat(row.get("job_execution_id")).isEqualTo(execution.getId());

        List<Map<String, Object>> errors = jdbc.queryForList("select * from import_errors where import_id = ?",
                importId);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("line_number")).isEqualTo(4);
        assertThat(errors.get(0).get("reason").toString()).contains("Invalid date");
        assertThat(errors.get(0).get("raw_line")).isEqualTo("bad-date,Shop,-1,RUB,");

        Map<String, Object> first = jdbc.queryForMap("select * from bank_transactions where import_id = ? "
                + "and mcc = '5411'", importId);
        assertThat(first.get("merchant")).isEqualTo("PYATEROCHKA");
        assertThat(first.get("category_source")).isEqualTo("NONE");
        assertThat(first.get("category_id")).isNull();
        assertThat(first.get("raw_description")).isEqualTo("PYATEROCHKA 1234 MOSCOW RUS");

        String event = jdbc.queryForObject("select payload::text from outbox_events where aggregate_id = ? "
                + "and topic = 'transactions.imported'", String.class, user.toString());
        assertThat(event).contains("\"importedRows\": 3").contains("\"failedRows\": 1")
                .contains("2026-10-14").contains("2026-10-16");
    }

    @Test
    void readsWindows1251StatementsWithSemicolonsAndCommaDecimals() throws Exception {
        UUID user = newUser();
        String csv = "Дата операции;Описание;Сумма;Валюта;MCC\n"
                + "14.10.2026;Пятерочка 77 МОСКВА;-1 250,75;RUR;5411\n";
        UUID importId = newImport(user, "SBER", csv.getBytes(WINDOWS_1251));

        runner.run(importId);

        Map<String, Object> row = jdbc.queryForMap("select * from bank_transactions where import_id = ?", importId);
        assertThat(row.get("merchant")).isEqualTo("ПЯТЕРОЧКА");
        assertThat(row.get("raw_description")).isEqualTo("Пятерочка 77 МОСКВА");
        assertThat(row.get("amount").toString()).isEqualTo("-1250.7500");
        assertThat(row.get("currency").toString().trim()).isEqualTo("RUB");
    }

    @Test
    void reuploadingTheSameStatementCreatesNoDuplicates() throws Exception {
        UUID user = newUser();
        byte[] file = tinkoff("2026-10-14,SHOP A,-10,RUB,", "2026-10-15,SHOP B,-20,RUB,");
        UUID first = newImport(user, "TINKOFF", file);
        UUID second = newImport(user, "TINKOFF", file);

        runner.run(first);
        runner.run(second);

        assertThat(importRow(first).get("rows_imported")).isEqualTo(2);
        assertThat(importRow(second).get("rows_imported")).isEqualTo(0);
        assertThat(importRow(second).get("rows_skipped")).isEqualTo(2);
        assertThat(importRow(second).get("status")).isEqualTo("DONE");
        Integer total = jdbc.queryForObject("select count(*) from bank_transactions where user_id = ?",
                Integer.class, user);
        assertThat(total).isEqualTo(2);
        Integer events = jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? "
                + "and topic = 'transactions.imported'", Integer.class, user.toString());
        assertThat(events).isEqualTo(1);
    }

    @Test
    void sameStatementImportedByAnotherUserIsNotADuplicate() throws Exception {
        byte[] file = tinkoff("2026-10-14,SHOP A,-10,RUB,");
        UUID alice = newImport(newUser(), "TINKOFF", file);
        UUID bob = newImport(newUser(), "TINKOFF", file);

        runner.run(alice);
        runner.run(bob);

        assertThat(importRow(alice).get("rows_imported")).isEqualTo(1);
        assertThat(importRow(bob).get("rows_imported")).isEqualTo(1);
    }

    @Test
    void identicalPurchasesOnTheSameDayAreBothKept() throws Exception {
        UUID user = newUser();
        UUID importId = newImport(user, "TINKOFF", tinkoff(
                "2026-10-14,COFFEE LIKE 12,-250,RUB,5814",
                "2026-10-14,COFFEE LIKE 12,-250,RUB,5814",
                "2026-10-14,COFFEE LIKE 12,-250,RUB,5814"));

        runner.run(importId);

        assertThat(importRow(importId).get("rows_imported")).isEqualTo(3);
        UUID again = newImport(user, "TINKOFF", tinkoff(
                "2026-10-14,COFFEE LIKE 12,-250,RUB,5814",
                "2026-10-14,COFFEE LIKE 12,-250,RUB,5814",
                "2026-10-14,COFFEE LIKE 12,-250,RUB,5814"));
        runner.run(again);
        assertThat(importRow(again).get("rows_skipped")).isEqualTo(3);
    }

    @Test
    void wrongHeaderFailsTheWholeImportWithAReason() throws Exception {
        UUID importId = newImport(newUser(), "TINKOFF", "a,b,c\n1,2,3\n".getBytes(StandardCharsets.UTF_8));

        JobExecution execution = runner.run(importId);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(importRow(importId).get("status")).isEqualTo("FAILED");
        String reason = jdbc.queryForObject("select reason from import_errors where import_id = ? "
                + "and line_number = 0", String.class, importId);
        assertThat(reason).contains("Unexpected header");
    }

    @Test
    void tooManyBrokenLinesFailTheImport() throws Exception {
        String[] lines = new String[150];
        java.util.Arrays.fill(lines, "not-a-date,Shop,-1,RUB,");
        UUID importId = newImport(newUser(), "TINKOFF", tinkoff(lines));

        JobExecution execution = runner.run(importId);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(importRow(importId).get("status")).isEqualTo("FAILED");
    }

    @Test
    void missingFileFailsTheImport() throws Exception {
        UUID user = newUser();
        UUID importId = UUID.randomUUID();
        String key = "statements/" + user + "/" + importId + "/gone.csv";
        jdbc.update("insert into statement_imports (id, user_id, bank_format, file_key, status) "
                + "values (?, ?, 'TINKOFF', ?, 'UPLOADED')", importId, user, key);
        when(storage.open(anyString())).thenThrow(new dev.finflow.common.error.NotFoundException("File gone"));

        runner.run(importId);

        assertThat(importRow(importId).get("status")).isEqualTo("FAILED");
    }
}
