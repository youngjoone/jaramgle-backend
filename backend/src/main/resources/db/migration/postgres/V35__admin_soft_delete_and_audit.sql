-- Soft-delete and moderation flags
ALTER TABLE users ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'ACTIVE' NOT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN DEFAULT FALSE NOT NULL;

ALTER TABLE stories ADD COLUMN IF NOT EXISTS is_hidden BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE stories ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN DEFAULT FALSE NOT NULL;

ALTER TABLE shared_stories ADD COLUMN IF NOT EXISTS is_hidden BOOLEAN DEFAULT FALSE NOT NULL;

-- Backfill defaults
UPDATE users SET status = 'ACTIVE' WHERE status IS NULL;
UPDATE users SET is_deleted = FALSE WHERE is_deleted IS NULL;
UPDATE stories SET is_hidden = FALSE WHERE is_hidden IS NULL;
UPDATE stories SET is_deleted = FALSE WHERE is_deleted IS NULL;
UPDATE shared_stories SET is_hidden = FALSE WHERE is_hidden IS NULL;

-- Admin audit log
CREATE TABLE admin_audit_logs (
    id BIGSERIAL PRIMARY KEY,
    admin_user_id BIGINT,
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(50),
    target_id VARCHAR(100),
    detail TEXT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_admin_audit_user FOREIGN KEY (admin_user_id) REFERENCES users(id)
);

CREATE INDEX idx_admin_audit_created_at ON admin_audit_logs(created_at DESC);
