CREATE TABLE IF NOT EXISTS local_story_sources (
    id BIGSERIAL PRIMARY KEY,
    region_code VARCHAR(32) NOT NULL,
    external_source VARCHAR(80) NOT NULL,
    external_id VARCHAR(120) NOT NULL,
    content_type_id VARCHAR(40),
    source_type VARCHAR(40) NOT NULL DEFAULT 'ATTRACTION',
    title VARCHAR(255) NOT NULL,
    normalized_title VARCHAR(255),
    district VARCHAR(100),
    subtitle TEXT,
    intro TEXT,
    feature TEXT,
    origin TEXT,
    story_context TEXT,
    address TEXT,
    thumbnail_url TEXT,
    image_url TEXT,
    photo_title VARCHAR(255),
    photo_location VARCHAR(255),
    photo_keywords TEXT,
    data_sources TEXT,
    lat DOUBLE PRECISION,
    lng DOUBLE PRECISION,
    quality_score INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_synced_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_local_story_sources_region_external UNIQUE (region_code, external_source, external_id)
);

CREATE INDEX IF NOT EXISTS idx_local_story_sources_region_active_score
    ON local_story_sources(region_code, active, quality_score DESC, title ASC);

CREATE INDEX IF NOT EXISTS idx_local_story_sources_region_external_id
    ON local_story_sources(region_code, external_id);

CREATE INDEX IF NOT EXISTS idx_local_story_sources_region_district
    ON local_story_sources(region_code, district);
