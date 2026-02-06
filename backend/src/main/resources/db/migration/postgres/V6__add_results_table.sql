CREATE TABLE results (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT,
    test_code VARCHAR(255) NOT NULL,
    score DOUBLE PRECISION NOT NULL,
    traits TEXT,
    poem TEXT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_results_user_id ON results(user_id);
CREATE INDEX idx_results_test_code ON results(test_code);
