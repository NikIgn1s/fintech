package dev.finflow.categorization.service;

import dev.finflow.categorization.client.MlCategorizerClient;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CategorizationService implements dev.finflow.categorization.api.StatementCategorizer {
    private final JdbcTemplate jdbc;
    private final MlCategorizerClient ml;
    private final dev.finflow.categorization.repository.CategoryRuleRepository ruleRepository;

    public record Rule(long id, String pattern, short categoryId, int priority) {}
    private record Row(long id, String description, BigDecimal amount, String mcc) {}

    @Transactional
    public void categorize(UUID importId, UUID userId) {
        List<Rule> rules = rules(userId);
        long after = 0;
        while (true) {
            var rows = jdbc.query("select id, raw_description, amount, mcc from bank_transactions "
                    + "where import_id = ? and user_id = ? and category_id is null and id > ? order by id limit 500",
                    (rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4)),
                    importId, userId, after);
            if (rows.isEmpty()) return;
            var pending = new ArrayList<MlCategorizerClient.Item>();
            for (Row row : rows) {
                Rule rule = rules.stream().filter(r -> row.description().toLowerCase(Locale.ROOT)
                        .contains(r.pattern().toLowerCase(Locale.ROOT))).findFirst().orElse(null);
                if (rule != null) {
                    assign(row.id(), userId, rule.categoryId(), "RULE", BigDecimal.ONE);
                } else {
                    String code = mccCategory(row.mcc());
                    if (code != null) assignCode(row.id(), userId, code, "MCC", BigDecimal.ONE);
                    else pending.add(new MlCategorizerClient.Item(Long.toString(row.id()),
                            row.description(), row.amount(), row.mcc()));
                }
            }
            Set<String> allowed = new HashSet<>();
            pending.forEach(item -> allowed.add(item.id()));
            for (var result : ml.categorize(pending)) {
                if (allowed.remove(result.id()) && result.confidence() != null
                        && result.confidence().signum() >= 0
                        && result.confidence().compareTo(BigDecimal.ONE) <= 0) {
                    assignCode(Long.parseLong(result.id()), userId, result.category(), "ML", result.confidence());
                }
            }
            after = rows.getLast().id();
        }
    }

    public static String mccCategory(String mcc) {
        if (mcc == null) return null;
        return switch (mcc.trim()) {
            case "5411", "5499" -> "GROCERIES";
            case "5812", "5814" -> "RESTAURANTS";
            case "5813" -> "CAFE";
            case "4121" -> "TAXI";
            case "4111", "4131" -> "TRANSPORT";
            case "5541", "5542" -> "FUEL";
            case "5912" -> "PHARMACY";
            default -> null;
        };
    }

    public List<Rule> rules(UUID userId) {
        return jdbc.query("select id, pattern, category_id, priority from category_rules "
                + "where user_id = ? order by priority desc, id",
                (rs, n) -> new Rule(rs.getLong(1), rs.getString(2), rs.getShort(3), rs.getInt(4)), userId);
    }

    @Transactional
    public Rule createRule(UUID userId, String pattern, short categoryId, int priority) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from categories where id=?)",
                Boolean.class,categoryId)))
            throw new dev.finflow.common.error.NotFoundException("Category not found");
        var entity=ruleRepository.saveAndFlush(dev.finflow.categorization.domain.CategoryRule
                .create(userId,pattern.trim(),categoryId,priority));
        return new Rule(entity.getId(),entity.getPattern(),entity.getCategoryId(),entity.getPriority());
    }

    @Transactional
    public void deleteRule(UUID userId,long id) {
        var entity=ruleRepository.findByIdAndUserId(id,userId)
                .orElseThrow(() -> new dev.finflow.common.error.NotFoundException("Rule not found"));
        ruleRepository.delete(entity);
    }

    private void assignCode(long id, UUID userId, String code, String source, BigDecimal confidence) {
        jdbc.update("update bank_transactions set category_id = c.id, category_source = ?, category_confidence = ? "
                + "from categories c where bank_transactions.id = ? and user_id = ? "
                + "and category_id is null and c.code = ?", source, confidence, id, userId, code);
    }

    private void assign(long id, UUID userId, short categoryId, String source, BigDecimal confidence) {
        jdbc.update("update bank_transactions set category_id = ?, category_source = ?, category_confidence = ? "
                + "where id = ? and user_id = ? and category_id is null",
                categoryId, source, confidence, id, userId);
    }
}
