CREATE TABLE reconciliation_runs (
    id             bigserial PRIMARY KEY,
    started_at     timestamptz NOT NULL,
    finished_at    timestamptz NOT NULL,
    mismatch_count integer     NOT NULL,
    details        jsonb       NOT NULL DEFAULT '[]'::jsonb
);
