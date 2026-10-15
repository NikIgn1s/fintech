CREATE TABLE ledger_transactions (
    id           bigserial PRIMARY KEY,
    type         varchar(30) NOT NULL,
    reference_id uuid        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_ledger_tx_type CHECK (type IN
        ('TRANSFER_RESERVE', 'TRANSFER_SETTLE', 'TRANSFER_REFUND', 'TOP_UP', 'WITHDRAWAL')),
    CONSTRAINT uq_ledger_tx_reference UNIQUE (type, reference_id)
);

CREATE TABLE ledger_entries (
    id             bigserial PRIMARY KEY,
    transaction_id bigint        NOT NULL REFERENCES ledger_transactions (id),
    account_id     bigint        NOT NULL REFERENCES accounts (id),
    amount         numeric(19,4) NOT NULL CHECK (amount <> 0),
    currency       char(3)       NOT NULL,
    created_at     timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX idx_entries_account_time ON ledger_entries (account_id, created_at);
CREATE INDEX idx_entries_transaction ON ledger_entries (transaction_id);

CREATE TABLE account_balances (
    account_id     bigint PRIMARY KEY REFERENCES accounts (id),
    balance        numeric(19,4) NOT NULL DEFAULT 0,
    allow_negative boolean       NOT NULL DEFAULT false,
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT chk_non_negative CHECK (allow_negative OR balance >= 0)
);

INSERT INTO account_balances (account_id, allow_negative)
SELECT id, false FROM accounts;

CREATE FUNCTION create_account_balance() RETURNS trigger AS $$
BEGIN
    INSERT INTO account_balances (account_id, allow_negative)
    VALUES (NEW.id, NEW.type LIKE 'SYSTEM\_%');
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_accounts_create_balance
AFTER INSERT ON accounts
FOR EACH ROW EXECUTE FUNCTION create_account_balance();

CREATE UNIQUE INDEX uq_system_account ON accounts (type, currency) WHERE user_id IS NULL;

INSERT INTO accounts (user_id, type, currency) VALUES
    (NULL, 'SYSTEM_GATEWAY', 'RUB'),
    (NULL, 'SYSTEM_SUSPENSE', 'RUB');
