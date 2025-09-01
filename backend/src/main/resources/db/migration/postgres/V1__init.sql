CREATE TABLE tests (
    code VARCHAR(255) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    version INT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE questions (
    id VARCHAR(255) PRIMARY KEY,
    test_code VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    is_reverse BOOLEAN DEFAULT FALSE,
    FOREIGN KEY (test_code) REFERENCES tests(code)
);

CREATE TABLE answers (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question_id VARCHAR(255) NOT NULL,
    value INT NOT NULL,
    submission_id VARCHAR(255) NOT NULL, -- Assuming a submission ID for grouping answers
    FOREIGN KEY (question_id) REFERENCES questions(id)
);

CREATE TABLE profiles (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id VARCHAR(255) UNIQUE NOT NULL, -- Assuming a user ID
    traits JSONB,
    last_updated TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
