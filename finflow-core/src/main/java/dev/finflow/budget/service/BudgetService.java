package dev.finflow.budget.service;

import dev.finflow.common.error.NotFoundException;
import dev.finflow.common.outbox.OutboxPublisher;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BudgetService {
    private final JdbcTemplate jdbc;
    private final OutboxPublisher outbox;
    private final dev.finflow.budget.repository.BudgetRepository budgets;
    public record Budget(long id, short categoryId, LocalDate month, String currency,
            BigDecimal limit, BigDecimal spent) {}
    public record Alert(long budgetId, UUID userId, short categoryId, LocalDate month,
            String currency, int threshold, String limit, String spent) {}

    @Transactional
    public Budget save(UUID userId, short categoryId, LocalDate month, String currency, BigDecimal limit) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from categories where id = ?)",
                Boolean.class, categoryId))) throw new NotFoundException("Category not found");
        jdbc.queryForObject("select id from users where id=? for update", UUID.class, userId);
        var entity=budgets.findByUserIdAndCategoryIdAndMonthAndCurrency(userId,categoryId,month,currency)
                .orElseGet(() -> dev.finflow.budget.domain.BudgetEntity.create(userId,categoryId,month,currency,limit));
        entity.changeLimit(limit);
        budgets.saveAndFlush(entity);
        recalculate(userId);
        return list(userId, month).stream().filter(b -> b.categoryId() == categoryId && b.currency().equals(currency))
                .findFirst().orElseThrow();
    }

    public List<Budget> list(UUID userId, LocalDate month) {
        return query(userId, month, false);
    }

    @Transactional
    public void recalculate(UUID userId) {
        for (Budget budget : query(userId, null, true)) {
            for (int threshold : new int[]{80, 100}) {
                if (budget.spent().multiply(BigDecimal.valueOf(100))
                        .compareTo(budget.limit().multiply(BigDecimal.valueOf(threshold))) >= 0
                        && jdbc.update("insert into budget_alert_log(budget_id, threshold) values (?, ?) "
                                + "on conflict do nothing", budget.id(), threshold) == 1) {
                    outbox.publish("budget", userId.toString(), "BudgetThresholdExceeded", "budget.alerts",
                            new Alert(budget.id(), userId, budget.categoryId(), budget.month(), budget.currency(),
                                    threshold, budget.limit().setScale(4).toPlainString(), budget.spent().setScale(4).toPlainString()));
                }
            }
        }
    }

    private List<Budget> query(UUID userId, LocalDate month, boolean lock) {
        String sql = "select b.*, coalesce((select sum(-t.amount) from bank_transactions t "
                + "where t.user_id = b.user_id and t.category_id = b.category_id and t.currency = b.currency "
                + "and t.amount < 0 and t.operation_date >= b.month "
                + "and t.operation_date < b.month + interval '1 month'), 0) spent "
                + "from budgets b where b.user_id = ?" + (month == null ? "" : " and b.month = ?")
                + " order by b.id" + (lock ? " for update of b" : "");
        Object[] args = month == null ? new Object[]{userId} : new Object[]{userId, month};
        return jdbc.query(sql, (rs, n) -> new Budget(rs.getLong("id"), rs.getShort("category_id"),
                rs.getDate("month").toLocalDate(), rs.getString("currency"),
                rs.getBigDecimal("amount_limit"), rs.getBigDecimal("spent")), args);
    }
}
