-- Shared stories for public sharing board
CREATE TABLE shared_stories (
    id BIGSERIAL PRIMARY KEY,
    story_id BIGINT NOT NULL,
    share_slug VARCHAR(64) NOT NULL,
    shared_title VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_shared_story_story FOREIGN KEY (story_id) REFERENCES stories(id) ON DELETE CASCADE,
    CONSTRAINT uq_shared_story_story UNIQUE (story_id),
    CONSTRAINT uq_shared_story_slug UNIQUE (share_slug)
);

CREATE INDEX idx_shared_stories_created_at ON shared_stories(created_at DESC);
