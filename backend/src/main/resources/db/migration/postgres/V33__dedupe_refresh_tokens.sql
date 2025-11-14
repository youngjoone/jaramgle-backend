DELETE FROM refresh_tokens
WHERE id NOT IN (
    SELECT MAX(id) FROM refresh_tokens GROUP BY token
);

ALTER TABLE refresh_tokens
    ADD CONSTRAINT uq_refresh_tokens_token UNIQUE (token);
