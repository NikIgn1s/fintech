package dev.finflow.categorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import dev.finflow.budget.service.BudgetService;
import dev.finflow.categorization.client.MlCategorizerClient;
import dev.finflow.categorization.service.CategorizationService;
import dev.finflow.support.AbstractPostgresTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class CategorizationBudgetTest extends AbstractPostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired CategorizationService categorization;
    @Autowired BudgetService budgets;
    @MockBean MlCategorizerClient ml;

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users(id,email,password_hash,full_name) values (?,?,'hash','Test')", id, id+"@test.dev");
        return id;
    }

    private short category(String code) {
        return jdbc.queryForObject("select id from categories where code = ?", Short.class, code);
    }

    private UUID statement(UUID user) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into statement_imports(id,user_id,bank_format,file_key,status) "
                + "values (?,?,'TINKOFF','file','DONE')", id, user);
        return id;
    }

    private long row(UUID user, UUID statement, String description, String mcc, String currency, String amount) {
        return jdbc.queryForObject("insert into bank_transactions(user_id,import_id,operation_date,amount,currency,"
                + "raw_description,mcc,dedup_hash) values (?,?,'2026-10-14',?,?,?,?,?) returning id",
                Long.class, user, statement, new BigDecimal(amount), currency, description, mcc, UUID.randomUUID().toString());
    }

    @Test
    void appliesRulesBeforeMccAndMlAndPreservesAssignedCategories() {
        UUID user = user();
        UUID statement = statement(user);
        jdbc.update("insert into category_rules(user_id,pattern,category_id,priority) values (?, 'shop', ?, 10)",
                user, category("CAFE"));
        long rule = row(user, statement, "SHOP", "5411", "RUB", "-10");
        long mcc = row(user, statement, "market", "5411", "RUB", "-10");
        long model = row(user, statement, "unknown", null, "RUB", "-10");
        when(ml.categorize(anyList())).thenReturn(List.of(new MlCategorizerClient.Result(
                Long.toString(model), "TAXI", new BigDecimal("0.55"))));
        categorization.categorize(statement, user);
        assertThat(jdbc.queryForObject("select category_source from bank_transactions where id=?", String.class, rule))
                .isEqualTo("RULE");
        assertThat(jdbc.queryForObject("select category_source from bank_transactions where id=?", String.class, mcc))
                .isEqualTo("MCC");
        assertThat(jdbc.queryForObject("select category_source from bank_transactions where id=?", String.class, model))
                .isEqualTo("ML");
        when(ml.categorize(anyList())).thenReturn(List.of());
        categorization.categorize(statement, user);
        assertThat(jdbc.queryForObject("select category_id from bank_transactions where id=?", Short.class, rule))
                .isEqualTo(category("CAFE"));
    }

    @Test
    void publishesEachThresholdOnceAndSeparatesUsersAndCurrencies() {
        UUID user = user();
        UUID statement = statement(user);
        long expense = row(user, statement, "food", "5411", "RUB", "-80");
        row(user, statement, "foreign food", "5411", "USD", "-900");
        categorization.categorize(statement, user);
        var budget = budgets.save(user, category("GROCERIES"), LocalDate.of(2026,10,1), "RUB", new BigDecimal("100"));
        assertThat(budget.spent()).isEqualByComparingTo("80");
        budgets.recalculate(user);
        assertThat(alerts(budget.id())).isEqualTo(1);
        jdbc.update("update bank_transactions set amount=-100 where id=?", expense);
        budgets.recalculate(user);
        budgets.recalculate(user);
        assertThat(alerts(budget.id())).isEqualTo(2);
        assertThat(budgets.list(user(), LocalDate.of(2026,10,1))).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type='BudgetThresholdExceeded' "
                + "and aggregate_id=?", Integer.class, user.toString())).isEqualTo(2);
    }

    private int alerts(long budget) {
        return jdbc.queryForObject("select count(*) from budget_alert_log where budget_id=?", Integer.class, budget);
    }
}
