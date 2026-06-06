UPDATE characters
SET
    prompt_keywords = 'Official Busan mascot Boogi, flat 2D white seagull character, red round glasses, yellow-orange beak, black sneakers, consistent storybook illustration, no 3D',
    visual_description = '공식 부산 마스코트 부기를 2D 동화책 삽화로 표현한다. 흰색 갈매기형 둥근 콩 모양 몸, 작은 검은 타원형 눈, 노란 주둥이, 머리 위 빨간 동그란 안경, 짧은 둥근 날개형 팔, 가는 노란 다리, 흰 밑창과 빨간 꽃 장식이 있는 검은 운동화를 항상 유지한다. 3D 렌더, CGI, 플라스틱 장난감, 클레이, 실사 마스코트 의상처럼 표현하지 않는다.',
    description_prompt = 'Official Busan mascot Boogi in flat 2D storybook style. Preserve the white rounded seagull body, tiny black eyes, yellow-orange beak, red round glasses, black sneakers with white soles and red flower accents. Never render as 3D, CGI, plastic toy, clay, photo, or mascot costume.',
    art_style = '2D 파스텔 동화책 삽화, flat colors, clean outline, no 3D',
    modeling_status = 'COMPLETED',
    scope = 'GLOBAL'
WHERE slug = 'busan-boogi';
