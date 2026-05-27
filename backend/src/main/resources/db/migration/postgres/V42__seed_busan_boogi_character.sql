INSERT INTO characters (
    slug,
    name,
    persona,
    catchphrase,
    prompt_keywords,
    image_url,
    visual_description,
    description_prompt,
    art_style,
    modeling_status,
    scope,
    owner_id
)
SELECT
    'busan-boogi',
    '부산시 마스코트 부기',
    '부산 바다를 사랑하고 아이들과 소통을 즐기는 밝고 친근한 부산 갈매기 캐릭터',
    '"부기랑 같이 부산 모험 떠나볼까?"',
    'Busan city mascot Boogi, white seagull character, friendly smile, city storytelling guide, children illustration',
    '/characters/busan-boogi.png',
    '둥근 얼굴의 흰 갈매기 캐릭터, 파란 포인트 컬러, 밝은 표정',
    'Use the official Busan mascot "Boogi" appearance consistently. Keep the character friendly, playful, and suitable for children storybooks.',
    '밝은 파스텔 동화풍',
    'COMPLETED',
    'GLOBAL',
    NULL
WHERE NOT EXISTS (
    SELECT 1 FROM characters WHERE slug = 'busan-boogi'
);
