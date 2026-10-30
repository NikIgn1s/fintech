package dev.finflow.analytics.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AnalyticsRefreshService {
    private final JdbcTemplate jdbc;

    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void refresh() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement=connection.createStatement()) {
                try (var result=statement.executeQuery("SELECT pg_try_advisory_lock(524102)")) {
                    result.next();
                    if (!result.getBoolean(1)) return null;
                }
                try {
                    statement.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_monthly_spending");
                } finally {
                    statement.execute("SELECT pg_advisory_unlock(524102)");
                }
            }
            return null;
        });
    }
}
