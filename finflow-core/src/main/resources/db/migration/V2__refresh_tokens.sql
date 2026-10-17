CREATE TABLE refresh_tokens (
    id          uuid PRIMARY KEY,
    user_id     uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id   uuid         NOT NULL,
    token_hash  varchar(64)  NOT NULL UNIQUE,
    created_at  timestamptz  NOT NULL,
    expires_at  timestamptz  NOT NULL,
    revoked_at  timestamptz
);

CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
