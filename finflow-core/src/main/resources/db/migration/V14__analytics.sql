CREATE MATERIALIZED VIEW mv_monthly_spending AS
SELECT user_id, COALESCE(category_id, 0)::smallint AS category_id, currency,
       date_trunc('month', operation_date)::date AS month,
       SUM(-amount) AS spent, COUNT(*) AS tx_count
FROM bank_transactions WHERE amount < 0
GROUP BY user_id, COALESCE(category_id, 0), currency, date_trunc('month', operation_date);

CREATE UNIQUE INDEX uq_monthly_spending ON mv_monthly_spending(user_id, category_id, currency, month);
CREATE INDEX idx_bank_tx_expense_analytics ON bank_transactions(user_id, currency, operation_date)
    INCLUDE(category_id, merchant, amount) WHERE amount < 0;
