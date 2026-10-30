# Analytics

All endpoints require an access token and read only the authenticated user's bank transactions.
Transfers and positive transaction amounts are excluded. Currency defaults to RUB; USD and EUR are
supported independently. No conversion or summation across currencies is performed.

| GET endpoint | Parameters | Result |
|---|---|---|
| /api/v1/analytics/spending | from, to, groupBy=category or month, currency | Expenses with monthly and period totals |
| /api/v1/analytics/trends | months=6, currency | Calendar-month expenses, previous month, percentage change, three-month moving average |
| /api/v1/analytics/top-merchants | from, to, currency | Top five ranks of merchants within each category |
| /api/v1/analytics/monthly-spending | months=12, currency | Monthly expenses and transaction counts from the dashboard view |

Dates use YYYY-MM-DD. The from boundary is inclusive and to is exclusive.
A date range must be positive and at most 3660 days. months must be between 1 and 120,
including the current calendar month in UTC.

The spending response identifies rows by level: CATEGORY, MONTH or TOTAL. An uncategorized expense
has a null categoryId at CATEGORY level; it is separate from subtotal rows. An empty range returns
a TOTAL row with zero spending. groupBy=category returns category details within each month,
monthly subtotals and the period total; groupBy=month omits category details.

Trends fill missing months with zero before applying LAG and the three-month AVG window.
The calculation reads three additional months before the requested range to preserve context.
Percentage change is null when the previous month's spending is zero. The current month is partial.
Categories appear when they have expenses in the requested range or its three-month context.

Top merchants use RANK, so ties at rank five can return more than five merchants.
Empty or missing merchant names form an UNKNOWN bucket.

## Materialized view

V14 creates mv_monthly_spending and a unique index on user, category, currency and month.
Category 0 is an internal sentinel for uncategorized transactions, returned as null by the API.
This keeps repeated concurrent refreshes safe for uncategorized groups.

The monthly-spending endpoint reads completed months from the view and the current month from
bank_transactions. It excludes current-month rows from the view to prevent double counting.
Historical data may lag by the refresh interval (15 minutes by default), plus refresh duration.
The other endpoints read live transactions directly.

The view is refreshed with REFRESH MATERIALIZED VIEW CONCURRENTLY. Readers remain available.
A scheduled job uses ShedLock; a PostgreSQL advisory lock also serializes refreshes across scheduler
and Kafka consumers. Overlapping refresh requests are coalesced; the next scheduled run catches
any changes committed while a refresh was running.

An independent finflow-analytics consumer group refreshes the view after transactions.imported
and transactions.categorized. Retrying a refresh is safe. Kafka errors use the existing retry/DLT
configuration. The scheduled refresh also repairs staleness if event delivery fails.

Configuration:
- finflow.analytics.refresh-enabled / ANALYTICS_REFRESH_ENABLED: enable the scheduled job.
- finflow.analytics.refresh-interval: delay between refreshes in milliseconds, default 900000.

## Query plans

V14 adds a partial covering index on (user_id, currency, operation_date), including category_id,
merchant and amount, restricted to expenses. PostgreSQL can use it for the user/currency/date filter.
The materialized view's unique index also supports per-user monthly reads.

Use EXPLAIN (ANALYZE, BUFFERS) with representative user IDs and date ranges against the SQL
in AnalyticsRepository. Compare actual row counts, execution time and heap/buffer reads with a
sequential-scan baseline in a disposable benchmark database. Small fixtures can favor sequential
scans; no production-scale performance figures are claimed by the integration tests.

Integration tests run on PostgreSQL 16 and cover ROLLUP totals, uncategorized rows, currency and
user isolation, missing calendar months, zero denominators, tied ranks and repeated view refreshes.
