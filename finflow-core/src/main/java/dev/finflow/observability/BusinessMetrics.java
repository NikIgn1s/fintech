package dev.finflow.observability;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BusinessMetrics implements MeterBinder {
    private final JdbcTemplate jdbc;
    private volatile Map<String,Object> snapshot=Map.of();
    private volatile long expires;

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String name:new String[]{"outbox_unpublished_events","pending_check_oldest_seconds","transfers_hold_ratio"}) {
            Gauge.builder(name,this,m -> m.value(name)).register(registry);
        }
        for (String status:new String[]{"PENDING_CHECK","ON_HOLD","COMPLETED","REJECTED","FAILED"}) {
            Gauge.builder("transfers",this,m -> m.value(status)).tag("status",status).register(registry);
        }
    }

    private synchronized double value(String key) {
        if (System.nanoTime()>=expires) {
            try {
                snapshot=jdbc.queryForMap("""
                        SELECT count(*) FILTER (WHERE status='PENDING_CHECK') AS "PENDING_CHECK",
                               count(*) FILTER (WHERE status='ON_HOLD') AS "ON_HOLD",
                               count(*) FILTER (WHERE status='COMPLETED') AS "COMPLETED",
                               count(*) FILTER (WHERE status='REJECTED') AS "REJECTED",
                               count(*) FILTER (WHERE status='FAILED') AS "FAILED",
                               coalesce(greatest(extract(epoch FROM now()-min(created_at)
                                   FILTER (WHERE status='PENDING_CHECK')),0),0) pending_check_oldest_seconds,
                               (SELECT count(*) FROM outbox_events WHERE published_at IS NULL) outbox_unpublished_events,
                               (SELECT coalesce(count(*) FILTER (WHERE decision='HOLD')::numeric/nullif(count(*),0),0)
                                   FROM fraud_checks) transfers_hold_ratio
                        FROM transfers
                        """);
            } catch (org.springframework.dao.DataAccessException error) {
                snapshot=Map.of();
            }
            expires=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        }
        Object value=snapshot.get(key);
        return value instanceof Number number ? number.doubleValue() : Double.NaN;
    }
}
