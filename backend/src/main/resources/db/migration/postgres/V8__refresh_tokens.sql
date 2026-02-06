CREATE TABLE refresh_tokens(
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    token VARCHAR(512) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_refresh_user ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_token ON refresh_tokens(token);
