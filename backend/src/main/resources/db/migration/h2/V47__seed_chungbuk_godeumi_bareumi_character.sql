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
    'chungbuk-godeumi-bareumi',
    '고드미·바르미',
    '충북의 올곧고 바른 마음을 상징하는 공식 대표 캐릭터. 아이들과 함께 호수, 숲, 마을길, 문화유산을 차분하고 친근하게 안내한다.',
    '"충북의 맑은 길을 함께 걸어볼까?"',
    'Official Chungbuk mascots Godeumi and Bareumi, two friendly flat 2D guide characters, bright child-friendly expression, preserve official reference image, no 3D',
    '/characters/chungbuk-godeumi-bareumi.jpg',
    '충북 공식 캐릭터 고드미·바르미의 공식 참조 이미지 외형을 유지한다. 두 캐릭터가 함께 있는 대표 안내 캐릭터로 표현하고, 색상·얼굴형·복장·상징 요소를 임의로 바꾸지 않는다. 2D 동화책 삽화 스타일로만 사용하며 3D, CGI, 실사 인형, 클레이, 사진 질감으로 재해석하지 않는다.',
    'Use the official Chungbuk Godeumi and Bareumi reference image consistently. Preserve both characters as a paired guide mascot, including their official silhouette, colors, face shapes, outfits, and symbolic details. Keep a flat 2D storybook illustration style; never render as 3D, CGI, clay, plastic toy, realistic photo, or mascot costume.',
    '2D 파스텔 동화책 삽화, 청풍명월의 호수와 숲, flat colors, clean outline, no 3D',
    'COMPLETED',
    'GLOBAL',
    NULL
WHERE NOT EXISTS (
    SELECT 1 FROM characters WHERE slug = 'chungbuk-godeumi-bareumi'
);
