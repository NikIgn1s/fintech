CREATE TABLE transfers (
    id              uuid PRIMARY KEY,
    from_account_id bigint        NOT NULL REFERENCES accounts (id),
    to_account_id   bigint        NOT NULL REFERENCES accounts (id),
    amount          numeric(19,4) NOT NULL CHECK (amount > 0),
    currency        char(3)       NOT NULL,
    status          varchar(20)   NOT NULL,
    description     varchar(255),
    fraud_score     numeric(5,4),
    hold_reason     varchar(255),
    initiated_by    uuid          NOT NULL REFERENCES users (id),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    version         bigint        NOT NULL DEFAULT 0,
    CONSTRAINT chk_transfers_accounts CHECK (from_account_id <> to_account_id),
    CONSTRAINT chk_transfers_status CHECK (status IN
        ('PENDING_CHECK', 'COMPLETED', 'ON_HOLD', 'REJECTED', 'FAILED'))
);

CREATE INDEX idx_transfers_status ON transfers (status) WHERE status IN ('PENDING_CHECK', 'ON_HOLD');
CREATE INDEX idx_transfers_from ON transfers (from_account_id, created_at DESC);
CREATE INDEX idx_transfers_to ON transfers (to_account_id, created_at DESC);
CREATE INDEX idx_transfers_initiator ON transfers (initiated_by, created_at DESC);

CREATE TABLE fraud_checks (
    id            bigserial PRIMARY KEY,
    transfer_id   uuid        NOT NULL REFERENCES transfers (id),
    score         numeric(5,4),
    model_version varchar(50),
    decision      varchar(20) NOT NULL,
    source        varchar(20) NOT NULL,
    features      jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_fraud_decision CHECK (decision IN ('APPROVE', 'HOLD')),
    CONSTRAINT chk_fraud_source CHECK (source IN ('ML', 'RULES_FALLBACK'))
);

CREATE INDEX idx_fraud_checks_transfer ON fraud_checks (transfer_id);

CREATE TABLE idempotency_keys (
    user_id         uuid        NOT NULL REFERENCES users (id),
    key             varchar(64) NOT NULL,
    request_hash    varchar(64) NOT NULL,
    response_status int,
    response_body   jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, key)
);

CREATE INDEX idx_idempotency_created ON idempotency_keys (created_at);
