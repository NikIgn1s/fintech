package dev.finflow.analytics.repository;

import dev.finflow.analytics.api.AnalyticsResponse.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AnalyticsRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public List<Spending> spending(UUID user, LocalDate from, LocalDate to, String currency, boolean byMonth) {
        String sql = byMonth ? """
                SELECT date_trunc('month',t.operation_date)::date AS month,
                       NULL::smallint category_id,NULL::text category,coalesce(sum(-t.amount),0) spent,
                       CASE WHEN grouping(date_trunc('month',t.operation_date))=1 THEN 'TOTAL' ELSE 'MONTH' END level
                FROM bank_transactions t
                WHERE t.user_id=:user AND t.currency=:currency AND t.amount<0
                  AND t.operation_date>=:from AND t.operation_date<:to
                GROUP BY ROLLUP(date_trunc('month',t.operation_date)) ORDER BY month NULLS LAST
                """ : """
                SELECT date_trunc('month',t.operation_date)::date AS month,
                       t.category_id,c.name_ru category,coalesce(sum(-t.amount),0) spent,
                       CASE WHEN grouping(date_trunc('month',t.operation_date))=1 THEN 'TOTAL'
                            WHEN grouping(t.category_id)=1 THEN 'MONTH' ELSE 'CATEGORY' END level
                FROM bank_transactions t LEFT JOIN categories c ON c.id=t.category_id
                WHERE t.user_id=:user AND t.currency=:currency AND t.amount<0
                  AND t.operation_date>=:from AND t.operation_date<:to
                GROUP BY ROLLUP(date_trunc('month',t.operation_date),(t.category_id,c.name_ru))
                ORDER BY month NULLS LAST,grouping(t.category_id),spent DESC,t.category_id NULLS LAST
                """;
        return jdbc.query(sql,params(user,from,to,currency),(rs,n) -> new Spending(
                rs.getObject("month",LocalDate.class),rs.getObject("category_id",Short.class),
                rs.getString("category"),rs.getBigDecimal("spent"),currency,rs.getString("level")));
    }

    public List<Trend> trends(UUID user, LocalDate from, LocalDate to, String currency) {
        String sql = """
                WITH monthly AS (
                    SELECT coalesce(category_id,0)::smallint category_id,
                           date_trunc('month',operation_date)::date AS month,sum(-amount) spent
                    FROM bank_transactions WHERE user_id=:user AND currency=:currency AND amount<0
                      AND operation_date>=CAST(:from AS date)-interval '3 months' AND operation_date<:to
                    GROUP BY coalesce(category_id,0),date_trunc('month',operation_date)
                ), grid AS (
                    SELECT c.category_id,m.month::date,coalesce(t.spent,0) spent
                    FROM (SELECT DISTINCT category_id FROM monthly) c
                    CROSS JOIN generate_series(CAST(:from AS date)-interval '3 months',
                                               CAST(:to AS date)-interval '1 month',interval '1 month') m(month)
                    LEFT JOIN monthly t ON t.category_id=c.category_id AND t.month=m.month::date
                ), windows AS (
                    SELECT *,lag(spent) OVER (PARTITION BY category_id ORDER BY month) previous,
                           avg(spent) OVER (PARTITION BY category_id ORDER BY month
                                ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) moving FROM grid
                )
                SELECT *,round(100*(spent-previous)/nullif(previous,0),1) change
                FROM windows WHERE month>=:from ORDER BY month,category_id
                """;
        return jdbc.query(sql,params(user,from,to,currency),(rs,n) -> new Trend(
                rs.getObject("month",LocalDate.class),category(rs.getShort("category_id")),
                rs.getBigDecimal("spent"),rs.getBigDecimal("previous"),rs.getBigDecimal("change"),
                rs.getBigDecimal("moving"),currency));
    }

    public List<Merchant> merchants(UUID user, LocalDate from, LocalDate to, String currency) {
        String sql = """
                WITH totals AS (
                    SELECT category_id,coalesce(nullif(trim(merchant),''),'UNKNOWN') merchant,sum(-amount) spent
                    FROM bank_transactions WHERE user_id=:user AND currency=:currency AND amount<0
                      AND operation_date>=:from AND operation_date<:to
                    GROUP BY category_id,coalesce(nullif(trim(merchant),''),'UNKNOWN')
                ), ranked AS (
                    SELECT *,rank() OVER (PARTITION BY category_id ORDER BY spent DESC) ranking FROM totals
                )
                SELECT * FROM ranked WHERE ranking<=5 ORDER BY category_id NULLS LAST,ranking,merchant
                """;
        return jdbc.query(sql,params(user,from,to,currency),(rs,n) -> new Merchant(
                rs.getObject("category_id",Short.class),rs.getString("merchant"),rs.getBigDecimal("spent"),
                rs.getLong("ranking"),currency));
    }

    public List<Monthly> monthly(UUID user, LocalDate from, LocalDate current, String currency) {
        String sql = """
                SELECT month,category_id,spent,tx_count FROM mv_monthly_spending
                WHERE user_id=:user AND currency=:currency AND month>=:from AND month<:current
                UNION ALL
                SELECT date_trunc('month',operation_date)::date AS month,coalesce(category_id,0)::smallint,
                       sum(-amount),count(*)
                FROM bank_transactions WHERE user_id=:user AND currency=:currency AND amount<0
                  AND operation_date>=:current AND operation_date<CAST(:current AS date)+interval '1 month'
                GROUP BY date_trunc('month',operation_date),coalesce(category_id,0)
                ORDER BY month,category_id
                """;
        return jdbc.query(sql,params(user,from,current,currency).addValue("current",current),
                (rs,n) -> new Monthly(rs.getObject("month",LocalDate.class),category(rs.getShort("category_id")),
                        rs.getBigDecimal("spent"),rs.getLong("tx_count"),currency));
    }

    private Short category(short id) {
        return id==0 ? null : id;
    }

    private MapSqlParameterSource params(UUID user, LocalDate from, LocalDate to, String currency) {
        return new MapSqlParameterSource("user",user).addValue("from",from).addValue("to",to)
                .addValue("currency",currency);
    }
}
