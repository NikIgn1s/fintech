CREATE TABLE budgets (
    id bigserial PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    category_id smallint NOT NULL REFERENCES categories(id),
    month date NOT NULL CHECK (extract(day FROM month) = 1),
    currency char(3) NOT NULL,
    amount_limit numeric(19,4) NOT NULL CHECK (amount_limit > 0),
    UNIQUE (user_id, category_id, month, currency)
);

CREATE TABLE budget_alert_log (
    budget_id bigint NOT NULL REFERENCES budgets(id),
    threshold int NOT NULL CHECK (threshold IN (80, 100)),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (budget_id, threshold)
);

CREATE INDEX idx_bank_tx_budget ON bank_transactions(user_id, category_id, currency, operation_date);
