CREATE TABLE test_defs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    title VARCHAR(128) NOT NULL,
    status VARCHAR(16) DEFAULT 'DRAFT',
    questions TEXT,
    scoring TEXT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX idx_test_defs_code_version ON test_defs(code, version);

CREATE TABLE test_audit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    action VARCHAR(32) NOT NULL,
    actor VARCHAR(64),
    snapshot TEXT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
