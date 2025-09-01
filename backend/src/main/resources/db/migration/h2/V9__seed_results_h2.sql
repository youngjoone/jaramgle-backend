INSERT INTO results (user_id, test_code, score, traits, poem, created_at) VALUES
(1, 'trait_v1', 85.5, '{"traitA": 0.8, "traitB": 0.2}', '아름다운 시 한 편', CURRENT_TIMESTAMP),
(1, 'trait_v1', 70.0, '{"traitA": 0.5, "traitB": 0.5}', '또 다른 시', CURRENT_TIMESTAMP);

-- Note: user_id is BIGINT in results table, but our testUser is String. 
-- For H2, we can use a literal number for BIGINT. 
-- In a real app, you'd fetch the actual user_id from the users table.