CREATE TABLE users (
    id            uuid PRIMARY KEY,
    email         varchar(255) NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
    full_name     varchar(255) NOT NULL,
    role          varchar(20)  NOT NULL DEFAULT 'USER',
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ANALYST', 'ADMIN'))
);

CREATE TABLE accounts (
    id         bigserial PRIMARY KEY,
    user_id    uuid REFERENCES users (id),
    type       varchar(30) NOT NULL,
    currency   char(3)     NOT NULL DEFAULT 'RUB',
    status     varchar(20) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_accounts_type CHECK (type IN ('USER_WALLET', 'USER_SAVINGS', 'SYSTEM_GATEWAY', 'SYSTEM_SUSPENSE')),
    CONSTRAINT chk_accounts_status CHECK (status IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT chk_accounts_owner CHECK ((type LIKE 'SYSTEM_%') = (user_id IS NULL))
);

CREATE INDEX idx_accounts_user ON accounts (user_id);
