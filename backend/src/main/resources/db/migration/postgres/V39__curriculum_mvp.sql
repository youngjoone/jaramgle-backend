ALTER TABLE stories
    ADD COLUMN IF NOT EXISTS origin VARCHAR(32) NOT NULL DEFAULT 'SINGLE';

UPDATE stories
SET origin = 'SINGLE'
WHERE origin IS NULL OR origin = '';

CREATE TABLE curriculums (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    weeks INT NOT NULL,
    category VARCHAR(50) NOT NULL,
    sub_topic VARCHAR(255),
    age_range VARCHAR(50),
    base_language VARCHAR(10) NOT NULL,
    generation_mode VARCHAR(20) NOT NULL DEFAULT 'ON_DEMAND',
    schedule_rule TEXT,
    next_run_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    default_character_ids_json TEXT,
    default_art_style VARCHAR(255),
    default_voice VARCHAR(100),
    base_language_locked BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_curriculums_user_created_at ON curriculums(user_id, created_at DESC);

CREATE TABLE curriculum_weeks (
    id BIGSERIAL PRIMARY KEY,
    curriculum_id BIGINT NOT NULL REFERENCES curriculums(id) ON DELETE CASCADE,
    week_no INT NOT NULL,
    primary_goal TEXT NOT NULL,
    sub_goals_json TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'NOT_STARTED',
    story_id BIGINT REFERENCES stories(id),
    current_version_no INT NOT NULL DEFAULT 0,
    continuity_stale BOOLEAN NOT NULL DEFAULT FALSE,
    auto_retry_used BOOLEAN NOT NULL DEFAULT FALSE,
    manual_retry_used BOOLEAN NOT NULL DEFAULT FALSE,
    skip_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (curriculum_id, week_no)
);

CREATE INDEX idx_curriculum_weeks_curriculum_status ON curriculum_weeks(curriculum_id, status);

CREATE TABLE curriculum_jobs (
    id BIGSERIAL PRIMARY KEY,
    curriculum_id BIGINT NOT NULL REFERENCES curriculums(id) ON DELETE CASCADE,
    week_id BIGINT NOT NULL REFERENCES curriculum_weeks(id) ON DELETE CASCADE,
    week_no INT NOT NULL,
    job_type VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    request_snapshot_json TEXT,
    charge_required BOOLEAN NOT NULL DEFAULT TRUE,
    charged BOOLEAN NOT NULL DEFAULT FALSE,
    refunded BOOLEAN NOT NULL DEFAULT FALSE,
    heart_amount INT NOT NULL DEFAULT 1,
    retry_of_job_id BIGINT REFERENCES curriculum_jobs(id),
    error_code VARCHAR(100),
    error_message TEXT,
    cancel_reason TEXT,
    queued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    timeout_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_curriculum_jobs_status_queued ON curriculum_jobs(status, queued_at);
CREATE INDEX idx_curriculum_jobs_curriculum_status ON curriculum_jobs(curriculum_id, status);

CREATE TABLE curriculum_episode_versions (
    id BIGSERIAL PRIMARY KEY,
    week_id BIGINT NOT NULL REFERENCES curriculum_weeks(id) ON DELETE CASCADE,
    story_id BIGINT NOT NULL REFERENCES stories(id),
    version_no INT NOT NULL,
    week_status VARCHAR(30) NOT NULL,
    story_text TEXT,
    asset_refs_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (week_id, version_no)
);

CREATE TABLE curriculum_series_memory (
    id BIGSERIAL PRIMARY KEY,
    curriculum_id BIGINT NOT NULL UNIQUE REFERENCES curriculums(id) ON DELETE CASCADE,
    last_summary TEXT,
    character_state_json TEXT,
    covered_topics_json TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE curriculum_job_ledgers (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES curriculum_jobs(id) ON DELETE CASCADE,
    action_type VARCHAR(20) NOT NULL,
    heart_transaction_id BIGINT REFERENCES heart_transactions(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (job_id, action_type)
);
