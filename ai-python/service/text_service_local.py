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
        "guide_description": "대구 신천에 사는 밝고 장난기 많은 도시 수달 안내자. 물길과 골목을 좋아하고 아이들에게 장소의 단서를 알려준다.",
    },
    "CHUNGBUK": {
        "name": "충북",
        "tone": "청풍명월, 호수와 숲, 산길, 문화유산, 느린 자연 탐험",
        "rules": "호수, 산, 숲, 물길, 문화유산을 따라가는 차분하고 맑은 자연 탐험의 정서를 살린다.",
        "guide": "충북 길잡이 친구들",
        "guide_description": "올곧고 바른 마음으로 충북의 길을 안내하는 독창적인 어린이 친구들. 공식 마스코트의 이름이나 외형을 복제하지 않는다.",
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


def _build_context_block(req: GenerateRequest) -> str:
    ctx = req.local_context
    if not ctx:
        return "- 선택된 지역 장소 컨텍스트 없음"
    return "\n".join([
        f"- 지역: {ctx.region_name or ctx.region_code or '미상'}",
        f"- 장소명: {ctx.title or '미상'}",
        f"- 구분/지역 단서: {ctx.district or '미상'}",
        f"- 한줄 소개: {ctx.introduction or ctx.subtitle or '미상'}",
        f"- 주요 특징: {ctx.feature_summary or '미상'}",
        f"- 유래/역사 포인트: {ctx.origin_story or '미상'}",
        f"- 스토리 참고 요약: {ctx.description or '미상'}",
        f"- 동화 소재 힌트: {ctx.story_seed or '미상'}",
        f"- 주소: {ctx.address or '미상'}",
        f"- 관광사진 제목: {ctx.photo_title or '미상'}",
        f"- 관광사진 촬영지: {ctx.photo_location or '미상'}",
        f"- 관광사진 키워드: {ctx.photo_keywords or '미상'}",
        f"- 공공데이터 출처: {ctx.data_sources or '미상'}",
    ])


def _build_local_prompt(req: GenerateRequest) -> str:
    lang_map = {"KO": "한국어", "EN": "영어", "JA": "일본어", "FR": "프랑스어", "ES": "스페인어", "DE": "독일어", "ZH": "중국어"}
    lang_code = str(req.language).upper()
    lang_label = lang_map.get(lang_code, "한국어")
    region = _region_profile(req)
    managed_guide_reference = region["code"] == "DAEGU"
    mission = _detect_mission(req)
    mission_rules = {
        "CITY_STORY": "지역 명소를 홍보문처럼 설명하지 말고, 주인공이 직접 걷고 발견하는 가족형 지역 탐험 이야기로 만든다.",
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
        "다른 이름이나 slug로 복제하지 않는다."
        if managed_guide_reference
        else f"{region['guide']}는 공식 마스코트 이름이나 외형을 복제하지 않은 독창적인 캐릭터로 설계하고 "
        "creative_concept.character_sheets에 정확히 한 번 포함한다."
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

[지역 장소 컨텍스트]
{_build_context_block(req)}

[지역 스토리 규칙]
- {region['rules']}
- {mission_rules[mission]}
- {region['guide']}는 지역을 안내하는 핵심 조력자로 등장한다. 단, 공식 캐릭터 참조 이미지가 없을 수 있으므로 외형은 과하게 구체화하지 말고 역할과 성격 중심으로 일관되게 표현한다.
- {guide_sheet_rule}
- 이야기 전체에 반복 등장하는 어린이 주인공은 정확히 1명으로 정하고, 이름·나이·얼굴·머리 모양·상의·하의·신발·소품 색상을 모든 페이지에서 고정한다.
- creative_concept.character_sheets에는 서비스가 별도 제공하는 공식 캐릭터를 제외한 반복 등장 인물을 빠짐없이 한 번씩 기록한다.
- 장소 정보는 공공데이터 기반으로 쓰되, 설명문을 그대로 복사하지 말고 아이와 가족이 경험하는 장면으로 바꾼다.
- 함께 제공된 공식 장소 사진을 가장 신뢰할 수 있는 시각 근거로 사용한다. 사진에 없는 유럽풍 골목, 시장, 건축물, 산, 강을 임의로 추가하지 않는다.
- 공공데이터의 짧은 특징 문구가 장소 사진과 충돌하면 사진의 실제 건축·지형·재료·색상을 우선한다.
- 선택 장소는 이야기의 중심 배경이다. 전체 페이지의 절반 이상에서 장소 자체 또는 사진에서 확인되는 핵심 외형이 분명히 드러나야 한다.
- 데이터에 없는 구체적 연도, 인물, 사건, 문화재 지정 정보는 새로 지어내지 않는다.
- 관광 소개문이 아니라 '가족이 함께 볼 수 있는 지역 이야기책'으로 구성한다.

[필수 등장/반영 요소]
{required_section}

[출력 규칙]
- story.pages는 정확히 {min_pages}개.
- story.pages.text / story.title / quiz는 반드시 {lang_label}로 작성.
- page.text는 각 페이지마다 충분한 서사(20단어 이상)를 갖는다.
- page.image_prompt는 1~2문장으로 그 페이지에 실제 등장하는 인물만 이름으로 명시하고 장면·행동·감정을 구체적으로 묘사한다.
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
    concept = story_data.setdefault("creative_concept", {})
    sheets = concept.get("character_sheets")
    if not isinstance(sheets, list):
        sheets = []

    if region["code"] == "DAEGU":
        filtered_sheets = []
        for sheet in sheets:
            if not isinstance(sheet, dict):
                continue
            slug = str(sheet.get("slug") or "").strip().lower()
            name = str(sheet.get("name") or "").strip().lower()
            is_dodalsu = slug in {"daegu-dodalsu", "dodalssu", "dodalsu"} or "도달쑤" in name or "dodalsu" in name
            if not is_dodalsu:
                filtered_sheets.append(sheet)
        sheets = filtered_sheets

    if not sheets:
        raise ValueError("Local story must include at least one consistent non-managed character sheet.")

    concept["character_sheets"] = sheets
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
    return _enforce_local_visual_contract(_parse_json_response(raw_json_text, request_id, "Local story"), req)


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
