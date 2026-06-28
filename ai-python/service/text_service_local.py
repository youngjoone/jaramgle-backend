import json
import logging
from io import BytesIO
from textwrap import dedent
from typing import Any, Dict, Optional
from urllib.request import Request, urlopen

from google import genai
from google.genai.errors import ClientError
from google.genai.types import GenerateContentConfig
from PIL import Image

from config import Config
from schemas import (
    GenerateRequest,
    GenerateResponse,
    StoryOutput,
    CreativeConcept,
    Moderation,
    TranslationOutput,
)
from service.text_service import _normalize_and_validate_story

logger = logging.getLogger(__name__)

LOCAL_ART_STYLE = (
    "2D hand-drawn family-friendly local storybook illustration, warm cinematic composition, "
    "clear rounded shapes, soft pastel texture, consistent outlines, no photorealism, no 3D render."
)

REGION_PROFILES: Dict[str, Dict[str, str]] = {
    "DAEGU": {
        "name": "대구",
        "tone": "따뜻한 벽돌빛 골목, 근대문화, 시장의 활기, 도시 속 시간여행",
        "rules": "붉은 벽돌 골목, 시장의 소리와 냄새, 팔공산과 도심 풍경 같은 대구의 도시적 온기를 살린다.",
        "guide": "도달쑤",
        "guide_description": "대구 신천에 사는 밝고 장난기 많은 도시 수달 안내자. 물길과 골목을 좋아하고 어린이 눈높이로 장소의 단서를 알려준다.",
    },
    "CHUNGBUK": {
        "name": "충북",
        "tone": "청풍명월, 호수와 숲, 산길, 문화유산, 느린 자연 탐험",
        "rules": "호수, 산, 숲, 물길, 문화유산을 따라가는 차분하고 맑은 자연 탐험의 정서를 살린다.",
        "guide": "고드미·바르미",
        "guide_description": "충북의 올곧고 바른 마음을 상징하는 공식 대표 캐릭터. 호수, 숲, 마을길, 문화유산을 차분하고 친근하게 안내한다.",
    },
}


class LocalProviderQuotaError(RuntimeError):
    """Raised when the upstream AI provider cannot serve due to billing/quota."""


def _is_quota_or_billing_error(exc: Exception) -> bool:
    status_code = getattr(exc, "status_code", None)
    message = str(exc).lower()
    return (
        status_code == 429
        or "resource_exhausted" in message
        or "quota" in message
        or "billing" in message
        or "prepayment credits are depleted" in message
    )


def _get_vertex_client() -> tuple[genai.Client, str]:
    if not Config.GOOGLE_PROJECT_ID or not Config.GOOGLE_LOCATION:
        raise RuntimeError("GOOGLE_PROJECT_ID and GOOGLE_LOCATION must be configured for local story generation.")
    model_name = getattr(Config, "GEMINI_TEXT_MODEL", None) or "gemini-2.5-flash"
    client = genai.Client(vertexai=True, project=Config.GOOGLE_PROJECT_ID, location=Config.GOOGLE_LOCATION)
    return client, model_name


def _parse_json_response(raw_json_text: str, request_id: str, label: str) -> Dict[str, Any]:
    try:
        return json.loads(raw_json_text)
    except json.JSONDecodeError:
        from json_repair import repair_json

        repaired = repair_json(raw_json_text)
        logger.warning("%s JSON decode failed. Attempting repair for request %s", label, request_id)
        return json.loads(repaired)


def _region_profile(req: GenerateRequest) -> Dict[str, str]:
    ctx = req.local_context
    code = (ctx.region_code if ctx else "") or ""
    code = code.strip().upper().replace("-", "_")
    if code in {"DAEGU", "DG"}:
        return REGION_PROFILES["DAEGU"] | {"code": "DAEGU"}
    if code in {"CHUNGBUK", "CHUNGCHEONGBUK", "CB"}:
        return REGION_PROFILES["CHUNGBUK"] | {"code": "CHUNGBUK"}
    name = (ctx.region_name if ctx else None) or "지역"
    return {
        "code": code or "LOCAL",
        "name": name,
        "tone": "지역의 장소성과 풍경",
        "rules": "선택된 지역 공공데이터의 장소성과 특징을 중심으로 이야기를 만든다.",
        "guide": "지역 안내 친구",
        "guide_description": "선택된 지역의 풍경과 장소 이야기를 아이 눈높이로 안내하는 친근한 캐릭터.",
    }


def _detect_mission(req: GenerateRequest) -> str:
    text = " ".join([*(req.topics or []), *(req.objectives or []), req.moral or ""]).lower()
    if "문화" in text or "역사" in text or "heritage" in text:
        return "HERITAGE"
    if "자연" in text or "생태" in text or "호수" in text or "숲" in text:
        return "NATURE"
    return "CITY_STORY"


def _trim_fact(value: Any, limit: int = 240) -> str:
    text = " ".join(str(value or "").split())
    if len(text) > limit:
        return text[:limit].rstrip() + "..."
    return text


def _fact_line(label: str, value: Any, limit: int = 240) -> Optional[str]:
    text = _trim_fact(value, limit)
    if not text:
        return None
    return f"- {label}: {text}"


def _build_context_block(req: GenerateRequest) -> str:
    ctx = req.local_context
    if not ctx:
        return "\n".join([
            "[공식 Fact Pack]",
            "- 선택된 지역 장소 컨텍스트 없음",
            "[생성 금지]",
            "- 특정 장소의 공식 역사, 연도, 인물, 사건, 문화재 지정 정보를 새로 만들지 않는다.",
        ])

    fact_lines = [
        _fact_line("한줄 소개", ctx.introduction or ctx.subtitle, 220),
        _fact_line("주요 특징", ctx.feature_summary, 260),
        _fact_line("유래/역사 포인트", ctx.origin_story, 260),
        _fact_line("스토리 참고 요약", ctx.description, 320),
        _fact_line("동화 소재 힌트", ctx.story_seed, 260),
    ]
    fact_lines = [line for line in fact_lines if line]
    visual_lines = [
        _fact_line("관광사진 제목", ctx.photo_title, 120),
        _fact_line("관광사진 촬영지", ctx.photo_location, 120),
        _fact_line("관광사진 키워드", ctx.photo_keywords, 180),
    ]
    visual_lines = [line for line in visual_lines if line]

    return "\n".join([
        "[공식 Fact Pack]",
        f"- 지역: {ctx.region_name or ctx.region_code or '미상'}",
        f"- 공식 장소명: {ctx.title or '미상'}",
        f"- 구분/지역 단서: {ctx.district or '미상'}",
        f"- 주소: {ctx.address or '미상'}",
        f"- 공공데이터 출처: {ctx.data_sources or '미상'}",
        "[사용 가능한 공식 사실]",
        *(fact_lines or ["- 제공된 설명이 부족함. 공식 사실처럼 보이는 세부 정보를 새로 만들지 말 것."]),
        "[사진/시각 근거]",
        *(visual_lines or ["- 사진 키워드 없음. 함께 제공된 장소 이미지를 우선 관찰할 것."]),
        "[생성 금지]",
        "- Fact Pack에 없는 연도, 인물, 사건, 설화, 문화재 지정 정보, 순위, 수상 이력을 공식 사실처럼 쓰지 않는다.",
        "- 정보가 부족하면 '그곳에 이런 역사가 있었다'고 단정하지 말고, 대표 캐릭터가 풍경을 관찰하고 궁금해하는 방식으로 처리한다.",
        "[상상 허용]",
        "- 대표 캐릭터의 행동, 감정, 페이지별 모험 순서, 대화 톤은 창작해도 된다.",
        "- 단, 창작 요소와 공식 사실이 섞여 독자가 허위 사실로 이해하지 않게 한다.",
    ])


def _selected_place_name(req: GenerateRequest) -> str:
    ctx = req.local_context
    return str((ctx.title if ctx else "") or "").strip()


def _story_mentions_place(story_data: Dict[str, Any], place_name: str) -> bool:
    if not place_name:
        return True
    story = story_data.get("story")
    if not isinstance(story, dict):
        return False
    text = str(story.get("title") or "")
    pages = story.get("pages")
    if isinstance(pages, list):
        for page in pages:
            if isinstance(page, dict):
                text += " " + str(page.get("text") or "")
                text += " " + str(page.get("image_prompt") or page.get("imagePrompt") or "")
    return place_name in text


def _build_local_prompt(req: GenerateRequest) -> str:
    lang_map = {"KO": "한국어", "EN": "영어", "JA": "일본어", "FR": "프랑스어", "ES": "스페인어", "DE": "독일어", "ZH": "중국어"}
    lang_code = str(req.language).upper()
    lang_label = lang_map.get(lang_code, "한국어")
    region = _region_profile(req)
    managed_guide_reference = region["code"] in {"DAEGU", "CHUNGBUK"}
    mission = _detect_mission(req)
    mission_rules = {
        "CITY_STORY": "지역 명소를 홍보문처럼 설명하지 말고, 지역 대표 캐릭터가 직접 걷고 발견하는 지역 탐험 이야기로 만든다.",
        "HERITAGE": "제공된 역사/문화 정보만 사실로 사용하고, 불확실한 연도·인물·사건은 지어내지 않는다. 과거와 현재를 연결하는 시간여행 구조를 권장한다.",
        "NATURE": "호수·산·숲·물길 같은 자연 요소를 감각적으로 묘사하고, 생태와 문화유산을 부드럽게 연결한다.",
    }
    required_items = [item.strip() for item in req.required_elements if str(item).strip()]
    required_section = "\n".join(f"- {item}" for item in required_items) if required_items else "- 선택 지역의 장소성과 공공데이터 핵심 정보"
    min_pages = req.min_pages or 10
    title_line = f'[제목] "{req.title}" (고정)' if req.title else "[제목] 미정(직접 생성)"
    topics_str = ", ".join(req.topics or [])
    objectives_str = ", ".join(req.objectives or [])
    moral_line = (req.moral or "").strip() or "지역을 더 잘 이해하고 아끼는 마음을 자연스럽게 드러낸다."
    art_style_input = (req.art_style or "").strip()
    art_style = f"{art_style_input}. {LOCAL_ART_STYLE}" if art_style_input else LOCAL_ART_STYLE
    guide_sheet_rule = (
        f"{region['guide']}는 서비스가 공식 참조 이미지로 별도 제공하므로 creative_concept.character_sheets에 넣지 말고 "
        "다른 이름이나 slug로 복제하지 않는다. 다른 반복 캐릭터도 새로 만들지 않는다."
        if managed_guide_reference
        else f"{region['guide']}는 공식 마스코트 이름이나 외형을 복제하지 않은 독창적인 캐릭터로 설계하고 "
        "creative_concept.character_sheets에 정확히 한 번 포함한다."
    )
    guide_presence_rule = (
        f"{region['guide']}는 지역 대표 안내 캐릭터다. 모든 page.text와 page.image_prompt에 정확히 '{region['guide']}' 이름으로 등장시킨다. "
        "첫 페이지와 마지막 페이지도 예외가 아니며, page.image_prompt에서 같은 캐릭터를 별칭으로 다시 만들지 않는다. "
        "이 지역 공모전용 이야기에 이름 있는 전면 캐릭터는 지역 대표 안내 캐릭터만 사용한다."
        if managed_guide_reference
        else "지역 안내 캐릭터는 이야기의 대표 캐릭터다. 이름 있는 단일 캐릭터로 정하고, 모든 page.text와 page.image_prompt에 같은 이름으로 등장시킨다. "
        "첫 페이지와 마지막 페이지도 예외가 아니며, creative_concept.character_sheets의 이름과 page.image_prompt의 이름을 반드시 일치시킨다."
    )
    character_sheet_rule = (
        "creative_concept.character_sheets는 빈 배열 []로 둔다. 서비스가 별도로 제공하는 지역 대표 캐릭터만 사용하고, 어린이 주인공·동물 친구·요정 같은 새 반복 캐릭터를 만들지 않는다."
        if managed_guide_reference
        else "creative_concept.character_sheets에는 서비스가 별도 제공하는 공식 캐릭터를 제외한 반복 등장 인물을 빠짐없이 한 번씩 기록한다."
    )

    prompt = f"""
너는 '{region['name']} 공공데이터 기반 AI 로컬 스토리맵'의 이야기 작가이자 아트 디렉터다.
반드시 JSON 하나만 출력하고, 설명 문장/마크다운/코드블록을 붙이지 마라.

[입력]
- 지역: {region['name']}
- 지역 톤: {region['tone']}
- 연령대: {req.age_range}
- 언어: {lang_label}
- {title_line}
- 최소 페이지 수: {min_pages}
- 주제: {topics_str or '지역 이야기'}
- 목표: {objectives_str or '공공데이터 기반 지역 이해'}
- 교훈: {moral_line}
- 공통 아트 스타일: {art_style}
- 지역 안내 캐릭터: {region['guide']} ({region['guide_description']})

[지역 공식 Fact Pack]
{_build_context_block(req)}

[지역 스토리 규칙]
- {region['rules']}
- {mission_rules[mission]}
- {region['guide']}는 지역을 안내하는 핵심 조력자로 등장한다. 단, 공식 캐릭터 참조 이미지가 없을 수 있으므로 외형은 과하게 구체화하지 말고 역할과 성격 중심으로 일관되게 표현한다.
- {guide_presence_rule}
- {guide_sheet_rule}
- 지역 대표 캐릭터가 문화재·명소·자연을 직접 소개하고 탐험한다. 어린이 주인공이나 새 조력자 캐릭터를 따로 만들지 않는다.
- 배경 인물은 필요할 때만 작고 흐릿한 군중으로 처리하고, 이름·외형·대사를 가진 반복 캐릭터로 만들지 않는다.
- {character_sheet_rule}
- 장소 정보는 [지역 공식 Fact Pack]의 사용 가능한 공식 사실만 근거로 쓰되, 설명문을 그대로 복사하지 말고 지역 대표 캐릭터가 직접 보고 안내하는 장면으로 바꾼다.
- Fact Pack에 없는 구체적 연도, 인물, 사건, 설화, 순위, 문화재 지정 정보는 새로 지어내지 않는다.
- 공식 사실이 부족한 장소는 허위 역사를 만들지 말고, 사진/시각 근거와 지역 분위기를 활용한 탐험 이야기로 만든다.
- 함께 제공된 공식 장소 사진을 가장 신뢰할 수 있는 시각 근거로 사용한다. 사진에 없는 유럽풍 골목, 시장, 건축물, 산, 강을 임의로 추가하지 않는다.
- 공공데이터의 짧은 특징 문구가 장소 사진과 충돌하면 사진의 실제 건축·지형·재료·색상을 우선한다.
- 선택 장소는 이야기의 중심 배경이다. 전체 페이지의 절반 이상에서 장소 자체 또는 사진에서 확인되는 핵심 외형이 분명히 드러나야 한다.
- 관광 소개문이 아니라 '대표 캐릭터가 안내하는 지역 이야기책'으로 구성한다.

[필수 등장/반영 요소]
{required_section}

[출력 규칙]
- story.pages는 정확히 {min_pages}개.
- story.pages.text / story.title / quiz는 반드시 {lang_label}로 작성.
- page.text는 각 페이지마다 충분한 서사(20단어 이상)를 갖는다.
- page.image_prompt는 1~2문장으로 그 페이지에 실제 등장하는 인물만 이름으로 명시하고 장면·행동·감정을 구체적으로 묘사한다.
- 지역 대표 안내 캐릭터는 모든 page.image_prompt에 반드시 이름으로 명시한다.
- 지역 대표 안내 캐릭터 외의 이름 있는 캐릭터를 page.text나 page.image_prompt에 만들지 않는다.
- page.image_prompt에서 같은 캐릭터를 두 번 설명하거나 별칭으로 중복 호출하지 않는다.
- 반복 등장 인물은 creative_concept.character_sheets의 이름을 철자까지 동일하게 사용한다.
- 선택 장소가 보이는 페이지의 image_prompt에는 장소 이름뿐 아니라 공식 사진에서 관찰한 지붕선, 외벽 재료, 구조, 주변 지형 중 2개 이상을 구체적으로 적는다.
- 모든 이미지는 여백·프레임·흰 띠가 없는 3:4 세로형 full-bleed 삽화이며, 인물과 랜드마크가 가장자리에서 잘리지 않도록 중앙 안전영역에 배치한다.
- 간판, 말풍선, 제목, 로고, 장식 글자는 생성하지 않는다. 학습상 꼭 필요한 정확한 글자가 아니면 이미지에 문자를 요구하지 않는다.
- 모든 page.image_prompt에는 선택 지역의 시각 정체성과 장소 배경을 반영한다.
- quiz는 정확히 3개, 3지선다, answer는 0~2 인덱스.

[출력 스키마]
{{
  "creative_concept": {{
    "art_style": "string",
    "mood_and_tone": "string",
    "character_sheets": [{{"name":"string","slug":"string","visual_description":"string","voice_profile":"string"}}]
  }},
  "story_outline": [{{"page":1,"summary":"string"}}],
  "story": {{
    "title": "string",
    "pages": [{{"page": 1, "text": "string", "image_prompt": "string"}}],
    "quiz": [{{"question":"string","options":["string","string","string"],"answer":0}}]
  }}
}}
"""
    return dedent(prompt).strip()


def _contains_name(value: str, name: str) -> bool:
    return bool(name and value and name.strip().lower() in value.strip().lower())


def _pick_local_guide_name(region: Dict[str, str], sheets: list) -> str:
    if region["code"] in {"DAEGU", "CHUNGBUK"}:
        return region["guide"]

    region_name = region.get("name", "")
    guide_keywords = [
        region_name,
        "지역",
        "길잡이",
        "안내",
        "가이드",
        "guide",
        "장소",
        "문화",
        "역사",
        "자연",
        "호수",
        "숲",
    ]
    valid_sheets = [sheet for sheet in sheets if isinstance(sheet, dict) and str(sheet.get("name") or "").strip()]
    for sheet in valid_sheets:
        haystack = " ".join([
            str(sheet.get("name") or ""),
            str(sheet.get("slug") or ""),
            str(sheet.get("visual_description") or ""),
            str(sheet.get("voice_profile") or ""),
        ]).lower()
        if any(keyword and keyword.lower() in haystack for keyword in guide_keywords):
            return str(sheet.get("name") or "").strip()

    if len(valid_sheets) >= 2:
        return str(valid_sheets[1].get("name") or "").strip()
    if valid_sheets:
        return str(valid_sheets[0].get("name") or "").strip()
    return region["guide"]


def _load_location_reference(req: GenerateRequest) -> Optional[Image.Image]:
    ctx = req.local_context
    image_url = (ctx.image_url if ctx else None) or (ctx.thumbnail_url if ctx else None)
    if not image_url:
        return None
    try:
        request = Request(image_url, headers={"User-Agent": "Jaramgle/1.0"})
        with urlopen(request, timeout=10) as response:
            data = response.read(12 * 1024 * 1024)
        image = Image.open(BytesIO(data))
        image.load()
        return image.convert("RGB")
    except Exception as exc:
        logger.warning("Failed to load local story location image %s: %s", image_url, exc)
        return None


def _enforce_local_visual_contract(story_data: Dict[str, Any], req: GenerateRequest) -> Dict[str, Any]:
    region = _region_profile(req)
    managed_guide_reference = region["code"] in {"DAEGU", "CHUNGBUK"}
    concept = story_data.setdefault("creative_concept", {})
    sheets = concept.get("character_sheets")
    if not isinstance(sheets, list):
        sheets = []

    if managed_guide_reference:
        filtered_sheets = []
        for sheet in sheets:
            if not isinstance(sheet, dict):
                continue
            slug = str(sheet.get("slug") or "").strip().lower()
            name = str(sheet.get("name") or "").strip().lower()
            is_dodalsu = slug in {"daegu-dodalsu", "dodalssu", "dodalsu"} or "도달쑤" in name or "dodalsu" in name
            is_chungbuk_mascot = (
                slug in {"chungbuk-godeumi-bareumi", "godeumi-bareumi", "godeumi", "bareumi"}
                or "고드미" in name
                or "바르미" in name
                or "godeumi" in name
                or "bareumi" in name
            )
            if not is_dodalsu and not is_chungbuk_mascot:
                filtered_sheets.append(sheet)
        sheets = filtered_sheets

    if not sheets and not managed_guide_reference:
        raise ValueError("Local story must include at least one consistent non-managed character sheet.")

    concept["character_sheets"] = [] if managed_guide_reference else sheets
    guide_name = _pick_local_guide_name(region, sheets)
    story = story_data.get("story")
    pages = story.get("pages") if isinstance(story, dict) else None
    if isinstance(pages, list):
        suffix = (
            " Full-bleed 3:4 portrait composition with no white margin, border, panel, caption, logo, sign text, or speech bubble."
            " Keep all important faces, hands, feet, props, and landmark features inside the central safe area."
            " Each named character appears exactly once."
        )
        for page in pages:
            if not isinstance(page, dict):
                continue
            image_prompt = str(page.get("image_prompt") or page.get("imagePrompt") or "").strip()
            if guide_name and not _contains_name(image_prompt, guide_name):
                image_prompt = (
                    f"{guide_name}는 지역 대표 안내 캐릭터로 장면 안에 함께 등장한다. "
                    f"{image_prompt}"
                ).strip()
            page["image_prompt"] = f"{image_prompt}{suffix}".strip()
    return story_data


def _call_local_gemini(req: GenerateRequest, request_id: str) -> Dict[str, Any]:
    client, model_name = _get_vertex_client()
    prompt = _build_local_prompt(req)
    region = _region_profile(req)
    contents: list[Any] = [prompt]
    location_reference = _load_location_reference(req)
    if location_reference is not None:
        contents.extend([
            "The following image is the official public-data photo of the selected location. "
            "Inspect its visible architecture, materials, roofline, colors, and terrain. "
            "Use it as visual evidence only; ignore and never reproduce any watermark or text.",
            location_reference,
        ])
    logger.info("Calling Local Vertex Gemini for request_id: %s, region: %s, model: %s", request_id, region["code"], model_name)
    response = client.models.generate_content(
        model=model_name,
        contents=contents,
        config=GenerateContentConfig(response_mime_type="application/json", temperature=0.65),
    )
    raw_json_text = response.text or ""
    logger.info("Local Gemini raw response for %s: %s", request_id, raw_json_text)
    story_data = _enforce_local_visual_contract(_parse_json_response(raw_json_text, request_id, "Local story"), req)
    place_name = _selected_place_name(req)
    if str(req.language).upper() == "KO" and place_name and not _story_mentions_place(story_data, place_name):
        raise ValueError(f"Local story must mention the selected public-data place: {place_name}")
    return story_data


def _translate_local_story(story: StoryOutput, source_lang: str, target_lang: str, request_id: str) -> Optional[TranslationOutput]:
    source = str(source_lang).upper()
    target = str(target_lang).upper()
    if not target or source == target:
        return None
    lang_map = {"KO": "한국어", "EN": "영어", "JA": "일본어", "FR": "프랑스어", "ES": "스페인어", "DE": "독일어", "ZH": "중국어"}
    pages_block = "\n".join([f"- Page {p.page_no}: {p.text}" for p in story.pages])
    prompt = dedent(f"""
    You are a professional translator for family-friendly local storybooks.
    Translate the given local story from {lang_map.get(source, source)} to {lang_map.get(target, target)}.
    Keep place names faithful and keep sentences warm, clear, and age-appropriate.
    Do not add explanations.
    Return ONLY JSON with keys: "title" and "pages" (array of objects: "page", "text").

    Original story:
    [Title] {story.title}
    {pages_block}
    """).strip()
    client, model_name = _get_vertex_client()
    response = client.models.generate_content(
        model=model_name,
        contents=prompt,
        config=GenerateContentConfig(response_mime_type="application/json", temperature=0.4),
    )
    data = _parse_json_response(response.text or "", request_id, "Local translation")
    return TranslationOutput(**data)


def generate_local_story(req: GenerateRequest, request_id: str) -> GenerateResponse:
    max_attempts = 2
    last_error: Optional[Exception] = None
    translation: Optional[TranslationOutput] = None
    for attempt in range(1, max_attempts + 1):
        try:
            story_data = _call_local_gemini(req, request_id)
            story = StoryOutput(**story_data["story"])
            story = _normalize_and_validate_story(story, req)
            concept_data = story_data.get("creative_concept")
            concept = CreativeConcept(**concept_data) if concept_data else None
            raw_json = json.dumps(story_data, ensure_ascii=False)
            if req.translation_language:
                try:
                    translation = _translate_local_story(story, req.language, req.translation_language, request_id)
                except Exception as translate_err:
                    logger.warning("Local translation failed for request %s: %s", request_id, translate_err)
                    translation = None
            return GenerateResponse(
                story=story,
                creative_concept=concept,
                reading_plan=[],
                translation=translation,
                raw_json=raw_json,
                moderation=Moderation(safe=True),
            )
        except ValueError as validation_error:
            last_error = validation_error
            logger.warning("Local story validation failed (attempt %s/%s) for request %s: %s", attempt, max_attempts, request_id, validation_error)
            if attempt == max_attempts:
                break
        except ClientError as exc:
            last_error = exc
            if _is_quota_or_billing_error(exc):
                logger.error("Local Vertex Gemini quota or billing error for request %s: %s", request_id, exc)
                raise LocalProviderQuotaError(
                    "Gemini 호출 권한/쿼터/결제 설정 문제로 지역 이야기를 생성할 수 없습니다. "
                    "Google Cloud Vertex AI 프로젝트의 결제, API 사용 설정, 모델 쿼터를 확인해 주세요."
                ) from exc
            logger.error("Local Vertex Gemini client error (attempt %s/%s) for request %s: %s", attempt, max_attempts, request_id, exc, exc_info=True)
            if attempt == max_attempts:
                break
        except Exception as exc:
            last_error = exc
            logger.error("Failed to generate local story (attempt %s/%s) for request %s: %s", attempt, max_attempts, request_id, exc, exc_info=True)
            if attempt == max_attempts:
                break
    if last_error is not None:
        raise last_error
    raise RuntimeError("Local story generation failed for unknown reasons")
