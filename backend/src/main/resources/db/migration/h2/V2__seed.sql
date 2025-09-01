INSERT INTO tests (code, title, version) VALUES ('trait_v1', '성향 테스트 v1', 1);

INSERT INTO questions (id, test_code, body, is_reverse) VALUES
('Q1', 'trait_v1', '나는 새로운 사람들을 만나는 것을 즐긴다.', FALSE),
('Q2', 'trait_v1', '나는 혼자 시간을 보내면 에너지가 고갈된다.', TRUE),
('Q3', 'trait_v1', '나는 대화의 중심에 있는 것을 좋아한다.', FALSE),
('Q4', 'trait_v1', '나는 계획을 세우고 따르는 것을 선호한다.', FALSE),
('Q5', 'trait_v1', '나는 즉흥적인 활동을 즐긴다.', FALSE);
