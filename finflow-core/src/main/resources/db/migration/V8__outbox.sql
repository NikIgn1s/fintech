CREATE TABLE outbox_events (
    id             uuid PRIMARY KEY,
    aggregate_type varchar(50)  NOT NULL,
    aggregate_id   varchar(64)  NOT NULL,
    event_type     varchar(100) NOT NULL,
    topic          varchar(100) NOT NULL,
    payload        jsonb        NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    published_at   timestamptz
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_published ON outbox_events (published_at) WHERE published_at IS NOT NULL;

CREATE TABLE processed_events (
    event_id     uuid         NOT NULL,
    consumer     varchar(100) NOT NULL,
    processed_at timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);
