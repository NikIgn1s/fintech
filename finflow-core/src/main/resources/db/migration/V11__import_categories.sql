CREATE TABLE categories (
    id        smallserial PRIMARY KEY,
    code      varchar(50)  NOT NULL UNIQUE,
    name_ru   varchar(100) NOT NULL,
    parent_id smallint REFERENCES categories (id)
);

INSERT INTO categories (code, name_ru) VALUES
    ('GROCERIES', 'Продукты'),
    ('CAFE', 'Кафе и кофейни'),
    ('RESTAURANTS', 'Рестораны и фастфуд'),
    ('TRANSPORT', 'Общественный транспорт'),
    ('TAXI', 'Такси'),
    ('FUEL', 'Автозаправки'),
    ('PHARMACY', 'Аптеки'),
    ('HEALTH', 'Здоровье и медицина'),
    ('ENTERTAINMENT', 'Развлечения'),
    ('CLOTHING', 'Одежда и обувь'),
    ('ELECTRONICS', 'Электроника'),
    ('UTILITIES', 'Коммунальные услуги'),
    ('TELECOM', 'Связь и интернет'),
    ('EDUCATION', 'Образование'),
    ('TRAVEL', 'Путешествия'),
    ('SUBSCRIPTIONS', 'Подписки'),
    ('INCOME', 'Доходы'),
    ('OTHER', 'Прочее');

CREATE TABLE statement_imports (
    id               uuid PRIMARY KEY,
    user_id          uuid         NOT NULL REFERENCES users (id),
    bank_format      varchar(30)  NOT NULL,
    file_key         varchar(255) NOT NULL,
    original_name    varchar(255),
    status           varchar(20)  NOT NULL,
    rows_total       int          NOT NULL DEFAULT 0,
    rows_imported    int          NOT NULL DEFAULT 0,
    rows_skipped     int          NOT NULL DEFAULT 0,
    rows_failed      int          NOT NULL DEFAULT 0,
    job_execution_id bigint,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    finished_at      timestamptz,
    CONSTRAINT chk_import_status CHECK (status IN ('UPLOADED', 'PROCESSING', 'DONE', 'FAILED'))
);

CREATE INDEX idx_statement_imports_user ON statement_imports (user_id, created_at DESC);

CREATE TABLE bank_transactions (
    id                  bigserial PRIMARY KEY,
    user_id             uuid          NOT NULL REFERENCES users (id),
    import_id           uuid REFERENCES statement_imports (id),
    operation_date      date          NOT NULL,
    amount              numeric(19,4) NOT NULL,
    currency            char(3)       NOT NULL,
    raw_description     varchar(500)  NOT NULL,
    merchant            varchar(255),
    mcc                 char(4),
    category_id         smallint REFERENCES categories (id),
    category_source     varchar(20)   NOT NULL DEFAULT 'NONE',
    category_confidence numeric(5,4),
    dedup_hash          varchar(64)   NOT NULL,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_bank_tx_dedup UNIQUE (user_id, dedup_hash),
    CONSTRAINT chk_bank_tx_source CHECK (category_source IN ('ML', 'RULE', 'MCC', 'USER', 'NONE'))
);

CREATE INDEX idx_bank_tx_user_date ON bank_transactions (user_id, operation_date);
CREATE INDEX idx_bank_tx_import ON bank_transactions (import_id);
CREATE INDEX idx_bank_tx_uncategorized ON bank_transactions (import_id) WHERE category_id IS NULL;

CREATE TABLE category_rules (
    id          bigserial PRIMARY KEY,
    user_id     uuid         NOT NULL REFERENCES users (id),
    pattern     varchar(255) NOT NULL,
    category_id smallint     NOT NULL REFERENCES categories (id),
    priority    int          NOT NULL DEFAULT 0
);

CREATE INDEX idx_category_rules_user ON category_rules (user_id, priority DESC);

CREATE TABLE import_errors (
    id          bigserial PRIMARY KEY,
    import_id   uuid         NOT NULL REFERENCES statement_imports (id) ON DELETE CASCADE,
    line_number int          NOT NULL,
    reason      varchar(255) NOT NULL,
    raw_line    text,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_import_errors_import ON import_errors (import_id, line_number);
