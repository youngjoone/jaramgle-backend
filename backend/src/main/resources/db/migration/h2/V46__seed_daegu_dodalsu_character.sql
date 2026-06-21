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
    'daegu-dodalsu',
    '대구시 캐릭터 도달쑤',
    '대구 신천에 사는 밝고 장난기 많은 수달 안내자. 환경과 도시의 이야기에 관심이 많고 아이들과 쉽게 어울린다.',
    '"신천 물길 따라 대구의 숨은 이야기를 찾아볼래?"',
    'Official Daegu mascot Dodalsu, flat 2D golden-brown otter, white muzzle and belly, round black nose, small rounded ears, wide friendly smile, small blue fish companion',
    '/characters/daegu-dodalsu.png',
    '공식 대구 캐릭터 도달쑤의 황금빛 갈색 수달 외형을 유지한다. 둥근 머리와 작은 귀, 검은 타원형 눈, 크고 둥근 검은 코, 흰색 입 주변과 배, 짧은 팔다리, 넓은 웃는 입을 일관되게 표현한다. 함께 있는 작은 파란 물고기 친구의 파란 몸과 흰 배도 유지한다.',
    'Use the official Daegu mascot Dodalsu reference image consistently. Preserve the golden-brown otter body, round head, tiny ears, black oval eyes, large round black nose, white muzzle and belly, short limbs, wide red smiling mouth, and the small blue fish companion. Keep a flat 2D illustrated appearance and never render as 3D, CGI, clay, plastic toy, photo, or mascot costume.',
    '2D 파스텔 동화책 삽화, flat colors, clean outline, no 3D',
    'COMPLETED',
    'GLOBAL',
    NULL
WHERE NOT EXISTS (
    SELECT 1 FROM characters WHERE slug = 'daegu-dodalsu'
);
