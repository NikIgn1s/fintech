CREATE TABLE transfer_reviews (
    id          bigserial PRIMARY KEY,
    transfer_id uuid         NOT NULL REFERENCES transfers (id),
    reviewer_id uuid         NOT NULL REFERENCES users (id),
    decision    varchar(20)  NOT NULL,
    reason      varchar(255),
    fraud_score numeric(5,4),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_review_decision CHECK (decision IN ('APPROVE', 'REJECT')),
    CONSTRAINT uq_review_transfer UNIQUE (transfer_id)
);

CREATE INDEX idx_transfer_reviews_reviewer ON transfer_reviews (reviewer_id, created_at DESC);
