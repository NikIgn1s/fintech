package dev.finflow.categorization.service;

import dev.finflow.common.outbox.OutboxPublisher;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="finflow.ml.retry-enabled", havingValue="true", matchIfMissing=true)
public class UncategorizedRetryJob {
    private final JdbcTemplate jdbc;
    private final CategorizationService categorization;
    private final OutboxPublisher outbox;

    @Scheduled(fixedDelayString="${finflow.ml.retry-interval:300000}", initialDelayString="${finflow.ml.retry-interval:300000}")
    @SchedulerLock(name="uncategorizedRetry", lockAtMostFor="10m")
    @Transactional
    public void retry() {
        var imports = jdbc.query("select distinct i.id, i.user_id from statement_imports i "
                + "join bank_transactions t on t.import_id=i.id "
                + "where i.status='DONE' and t.category_id is null order by i.id limit 10",
                (rs, n) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)});
        for (UUID[] item : imports) {
            Integer before = remaining(item[0]);
            categorization.categorize(item[0], item[1]);
            if (remaining(item[0]) < before) {
                outbox.publish("import", item[1].toString(), "TransactionsCategorized", "transactions.categorized",
                        new Categorized(item[0], item[1]));
            }
        }
    }

    public record Categorized(UUID importId, UUID userId) {}

    private Integer remaining(UUID importId) {
        return jdbc.queryForObject("select count(*) from bank_transactions where import_id=? and category_id is null",
                Integer.class, importId);
    }
}
