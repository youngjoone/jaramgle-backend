CREATE TABLE shared_story_bookmarks (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    shared_story_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_bookmark_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_bookmark_shared_story FOREIGN KEY (shared_story_id) REFERENCES shared_stories(id),
    CONSTRAINT uk_bookmark_user_story UNIQUE (user_id, shared_story_id)
);

CREATE INDEX idx_bookmark_user_id ON shared_story_bookmarks(user_id);
CREATE INDEX idx_bookmark_shared_story_id ON shared_story_bookmarks(shared_story_id);
