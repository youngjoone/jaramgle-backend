import json
import logging
from textwrap import dedent
from typing import Any, Dict, Optional

from google import genai
from google.genai.errors import ClientError
from google.genai.types import GenerateContentConfig

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

BUSAN_2D_ART_STYLE = (
    "2D hand-drawn children's storybook illustration, clean rounded vector-like shapes, "
    "soft pastel colors, gentle watercolor texture, consistent thin outline, flat 2D lighting. "
    "Never use 3D render, CGI, plastic toy, clay, mascot costume photo, realistic texture, or volumetric lighting."
)

BOOGI_2D_VISUAL_GUIDE = (
    "Official Busan mascot Boogi as a flat 2D storybook character: a cute white seagull-like mascot with a tall rounded bean-shaped body, "
    "smooth white face and belly, tiny black oval eyes, a small yellow-orange rounded beak, short rounded wing-like arms, "
    "thin yellow legs, black sneakers with white soles and small red flower accents, and red round glasses resting on top of the head. "
    "Preserve this silhouette, colors, glasses, beak, shoes, and friendly expression exactly, but render it only as a 2D illustration."
)


class BusanProviderQuotaError(RuntimeError):
    """Raised when the upstream AI provider cannot serve due to billing/quota."""


def _is_prepayment_depleted(exc: Exception) -> bool:
    message = str(exc).lower()
    return "prepayment credits are depleted" in message or (
        "resource_exhausted" in message and "billing" in message
    )


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


def _get_busan_vertex_client() -> tuple[genai.Client, str]:
    if not Config.GOOGLE_PROJECT_ID or not Config.GOOGLE_LOCATION:
        raise RuntimeError("GOOGLE_PROJECT_ID and GOOGLE_LOCATION must be configured for Busan Vertex Gemini generation.")

    model_name = getattr(Config, "GEMINI_TEXT_MODEL", None) or "gemini-2.5-flash"
    client = genai.Client(
        vertexai=True,
        project=Config.GOOGLE_PROJECT_ID,
        location=Config.GOOGLE_LOCATION,
    )
    return client, model_name


def _parse_json_response(raw_json_text: str, request_id: str, label: str) -> Dict[str, Any]:
    try:
        return json.loads(raw_json_text)
    except json.JSONDecodeError:
        from json_repair import repair_json

        repaired = repair_json(raw_json_text)
        logger.warning("%s JSON decode failed. Attempting repair for request %s", label, request_id)
        return json.loads(repaired)


def _enforce_busan_visual_contract(story_data: Dict[str, Any]) -> Dict[str, Any]:
    concept = story_data.setdefault("creative_concept", {})
    existing_style = str(concept.get("art_style") or "").strip()
    if "3d" in existing_style.lower() or "cgi" in existing_style.lower():
        existing_style = ""
    concept["art_style"] = f"{existing_style}. {BUSAN_2D_ART_STYLE}" if existing_style else BUSAN_2D_ART_STYLE

    sheets = concept.get("character_sheets")
    if not isinstance(sheets, list):
        sheets = []
        concept["character_sheets"] = sheets

    boogi_sheet = None
    for sheet in sheets:
        if not isinstance(sheet, dict):
            continue
        slug = str(sheet.get("slug") or "").strip().lower()
        name = str(sheet.get("name") or "").strip().lower()
        if slug in {"busan-boogi", "boogi"} or "부기" in name or "boogi" in name:
            boogi_sheet = sheet
            break

    if boogi_sheet is None:
        boogi_sheet = {}
        sheets.append(boogi_sheet)

    boogi_sheet["slug"] = "busan-boogi"
    boogi_sheet["name"] = "부산시 마스코트 부기"
    boogi_sheet["visual_description"] = BOOGI_2D_VISUAL_GUIDE
    boogi_sheet.setdefault("voice_profile", "밝고 친근하며 아이들을 부산 모험으로 안내하는 명랑한 목소리")

    story = story_data.get("story")
    pages = story.get("pages") if isinstance(story, dict) else None
    if isinstance(pages, list):
        for page in pages:
            if not isinstance(page, dict):
                continue
            image_prompt = str(page.get("image_prompt") or page.get("imagePrompt") or "").strip()
            style_suffix = (
                "Render as a consistent flat 2D children's storybook illustration with pastel colors and clean outlines. "
                "Boogi must match the official 2D guide with red round glasses, yellow beak, white rounded body, and black sneakers. "
                "No 3D, CGI, plastic toy, clay, mascot costume, or photorealistic texture."
            )
            if image_prompt:
                page["image_prompt"] = f"{image_prompt} {style_suffix}"
            else:
                page["image_prompt"] = style_suffix

    return story_data


def _detect_busan_mission(req: GenerateRequest) -> str:
    topics = [str(topic).strip().lower() for topic in (req.topics or []) if str(topic).strip()]
    objectives = [str(obj).strip().lower() for obj in (req.objectives or []) if str(obj).strip()]
    moral = (req.moral or "").strip().lower()

    # 부산 전용 UI에서 전달하는 주제값을 우선 신뢰한다.
    # (다문화는 탭에서 선택한 경우에만 활성화되도록 강제)
    if any("다문화" in topic for topic in topics):
        return "MULTICULTURAL"
    if any(("문화유산" in topic or "역사" in topic) for topic in topics):
        return "HERITAGE"

    # 구버전/직접호출 요청과의 호환을 위한 보조 판별
    signal = " ".join([*objectives, moral]).strip()
    if "다문화" in signal:
        return "MULTICULTURAL"
    if "문화유산" in signal or "역사" in signal:
        return "HERITAGE"
    return "CITY_INTRO"


def _build_busan_prompt(req: GenerateRequest) -> str:
    lang_map = {
        "KO": "한국어",
        "EN": "영어",
        "JA": "일본어",
        "FR": "프랑스어",
        "ES": "스페인어",
        "DE": "독일어",
        "ZH": "중국어",
    }
    lang_code = str(req.language).upper()
    lang_label = lang_map.get(lang_code, "한국어")

    mission = _detect_busan_mission(req)
    title_line = f'[제목] "{req.title}" (고정)' if req.title else "[제목] 미정(직접 생성)"

    required_items = [item.strip() for item in req.required_elements if str(item).strip()]
    if "부산 공식 캐릭터 부기" not in required_items:
        required_items.append("부산 공식 캐릭터 부기")

    busan_ctx = req.busan_context
    busan_context_block = "- 선택된 장소 컨텍스트 없음"
    if busan_ctx:
        context_lines = [
            f"- 장소명: {busan_ctx.title or '미상'}",
            f"- 구/군: {busan_ctx.district or '미상'}",
            f"- 한줄 소개: {busan_ctx.introduction or busan_ctx.subtitle or '미상'}",
            f"- 주요 특징: {busan_ctx.feature_summary or '미상'}",
            f"- 유래/역사 포인트: {busan_ctx.origin_story or '미상'}",
            f"- 스토리 참고 요약: {busan_ctx.description or '미상'}",
            f"- 주소: {busan_ctx.address or '미상'}",
        ]
        busan_context_block = "\n".join(context_lines)

    mission_rules = {
        "CITY_INTRO": dedent("""
        - 부산 공식 캐릭터 '부기'가 매 페이지의 핵심 행동에 참여한다.
        - 선택된 부산 장소를 배경으로 도시의 특징(바다·시장·다리·골목)을 자연스럽게 소개한다.
        - 홍보문처럼 나열하지 말고 아이가 직접 체험하는 사건 중심으로 구성한다.
        - 다문화 전개는 추가하지 말고, 등장인물은 부기와 명소 맥락에 필요한 최소 보조 인물만 사용한다.
        """).strip(),
        "HERITAGE": dedent("""
        - 부산 공식 캐릭터 '부기'가 문화유산 탐험의 안내자 역할을 맡는다.
        - 제공된 장소/설명 기반 사실 정보를 최소 1개 이상 포함한다.
        - 연도/고유명사 등 확신 없는 사실은 단정하지 말고 일반 설명으로 완화한다.
        - 다문화 전개는 추가하지 말고, 등장인물은 부기와 명소 맥락에 필요한 최소 보조 인물만 사용한다.
        """).strip(),
        "MULTICULTURAL": dedent("""
        - 부산 공식 캐릭터 '부기'와 다양한 문화권의 어린이 2명 이상이 함께 등장한다.
        - 서로의 언어·음식·놀이 문화를 존중하며 협력해 문제를 해결하는 장면을 포함한다.
        - 고정관념·우열·편견 표현은 절대 금지한다.
        """).strip(),
    }

    required_section = "\n".join(f"- {item}" for item in required_items)
    topics_str = ", ".join(req.topics or [])
    objectives_str = ", ".join(req.objectives or [])
    moral_line = (req.moral or "").strip() or "이야기 흐름 속에서 자연스럽게 긍정적인 메시지를 드러낸다."
    art_style_input = (req.art_style or "").strip()
    art_style = f"{art_style_input}. {BUSAN_2D_ART_STYLE}" if art_style_input else BUSAN_2D_ART_STYLE
    min_pages = req.min_pages or 10

    character_lines = []
    if req.characters:
        for c in req.characters:
            details = []
            if c.persona:
                details.append(f"성격: {c.persona}")
            if c.catchphrase:
                details.append(f"말버릇: {c.catchphrase}")
            if c.prompt_keywords:
                details.append(f"키워드: {c.prompt_keywords}")
            if c.visual_description:
                details.append(f"외형 고정 설명: {c.visual_description}")
            if c.slug in {"busan-boogi", "boogi"} or "부기" in c.name:
                details.append(f"부기 2D 고정 가이드: {BOOGI_2D_VISUAL_GUIDE}")
            info = " | ".join(details) if details else "추가 설명 없음"
            character_lines.append(f"- {c.name} ({c.slug}): {info}")

    character_section = "\n".join(character_lines) if character_lines else "- (선택 캐릭터 없음)"

    prompt = f"""
너는 '부산 공모전 전용' 아동 동화 작가이자 아트 디렉터다.
반드시 JSON 하나만 출력하고, 설명 문장/마크다운/코드블록을 붙이지 마라.

[입력]
- 연령대: {req.age_range}
- 언어: {lang_label}
- {title_line}
- 최소 페이지 수: {min_pages}
- 주제: {topics_str or '자유 선택'}
- 목표: {objectives_str or '자유 선택'}
- 교훈: {moral_line}
- 공통 아트 스타일: {art_style}

[부산 장소 컨텍스트]
{busan_context_block}

[부산 미션 규칙]
{mission_rules[mission]}

[2D 스타일 고정 규칙]
- 모든 삽화는 반드시 같은 2D 동화책 삽화 스타일이어야 한다.
- 3D 렌더, CGI, 피규어, 플라스틱 장난감, 클레이, 실사 마스코트 의상, 사진 같은 질감은 절대 금지한다.
- `creative_concept.art_style`에는 반드시 2D/flat/hand-drawn 성격이 드러나야 한다.
- 모든 `page.image_prompt`에는 2D 동화책 삽화, flat colors, consistent outline, no 3D/CGI 조건을 자연스럽게 포함한다.

[부기 캐릭터 고정 규칙]
- 부기는 새 캐릭터가 아니다. 반드시 slug를 "busan-boogi", name을 "부산시 마스코트 부기"로 사용한다.
- 부기의 외형은 다음 설명을 기준으로 고정한다: {BOOGI_2D_VISUAL_GUIDE}
- 부기를 다른 새, 오리, 펭귄, 사람, 로봇, 3D 인형, 털/깃털이 사실적인 동물로 재해석하지 않는다.
- 부기의 빨간 동그란 안경, 노란 주둥이, 흰 둥근 몸, 검은 운동화 디테일을 모든 페이지에서 유지한다.

[필수 등장 요소]
{required_section}

[캐릭터]
{character_section}

[출력 규칙]
- story.pages는 정확히 {min_pages}개.
- story.pages.text / story.title / quiz는 반드시 {lang_label}로 작성.
- page.text는 각 페이지마다 충분한 서사(20단어 이상)를 갖는다.
- page.image_prompt는 1~2문장으로 장면·행동·감정을 구체적으로 묘사한다.
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


def _call_busan_gemini(req: GenerateRequest, request_id: str) -> Dict[str, Any]:
    client, model_name = _get_busan_vertex_client()
    prompt = _build_busan_prompt(req)
    logger.info(
        "Calling Busan Vertex Gemini for request_id: %s, model: %s, location: %s",
        request_id,
        model_name,
        Config.GOOGLE_LOCATION,
    )
    response = client.models.generate_content(
        model=model_name,
        contents=prompt,
        config=GenerateContentConfig(
            response_mime_type="application/json",
            temperature=0.65,
        ),
    )

    raw_json_text = response.text or ""
    logger.info("Busan Gemini raw response for %s: %s", request_id, raw_json_text)
    return _parse_json_response(raw_json_text, request_id, "Busan story")


def _translate_busan_story_vertex(
    story: StoryOutput,
    source_lang: str,
    target_lang: str,
    request_id: str,
) -> Optional[TranslationOutput]:
    source = str(source_lang).upper()
    target = str(target_lang).upper()
    if not target or source == target:
        return None

    lang_map = {
        "KO": "한국어",
        "EN": "영어",
        "JA": "일본어",
        "FR": "프랑스어",
        "ES": "스페인어",
        "DE": "독일어",
        "ZH": "중국어",
    }
    source_label = lang_map.get(source, source)
    target_label = lang_map.get(target, target)
    pages_block = "\n".join([f"- Page {p.page_no}: {p.text}" for p in story.pages])
    prompt = dedent(f"""
    You are a professional translator for children's storybooks.
    Translate the given Busan-themed story from {source_label} to {target_label}.
    Keep sentences warm, clear, age-appropriate, and faithful to Busan place names and the Boogi character.
    Do not add explanations.
    Return ONLY JSON with keys: "title" and "pages" (array of objects: "page", "text").

    Original story:
    [Title] {story.title}
    {pages_block}
    """).strip()

    client, model_name = _get_busan_vertex_client()
    logger.info(
        "Translating Busan story via Vertex Gemini for request %s: %s->%s, model: %s",
        request_id,
        source,
        target,
        model_name,
    )
    response = client.models.generate_content(
        model=model_name,
        contents=prompt,
        config=GenerateContentConfig(
            response_mime_type="application/json",
            temperature=0.4,
        ),
    )
    raw_json_text = response.text or ""
    logger.info("Busan translation raw response for %s: %s", request_id, raw_json_text)
    data = _parse_json_response(raw_json_text, request_id, "Busan translation")
    return TranslationOutput(**data)


def generate_busan_story(req: GenerateRequest, request_id: str) -> GenerateResponse:
    provider = Config.LLM_PROVIDER.lower()
    max_attempts = 2
    last_error: Optional[Exception] = None
    translation: Optional[TranslationOutput] = None

    for attempt in range(1, max_attempts + 1):
        try:
            if provider == "gemini":
                story_data = _call_busan_gemini(req, request_id)
            else:
                # 부산 전용은 프롬프트 통제를 위해 Gemini 경로만 사용
                story_data = _call_busan_gemini(req, request_id)
            story_data = _enforce_busan_visual_contract(story_data)

            story = StoryOutput(**story_data["story"])
            story = _normalize_and_validate_story(story, req)
            concept_data = story_data.get("creative_concept")
            concept = CreativeConcept(**concept_data) if concept_data else None
            raw_json = json.dumps(story_data, ensure_ascii=False)

            if req.translation_language:
                try:
                    translation = _translate_busan_story_vertex(story, req.language, req.translation_language, request_id)
                except Exception as translate_err:
                    logger.warning("Busan translation failed for request %s: %s", request_id, translate_err)
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
            logger.warning(
                "Busan story validation failed (attempt %s/%s) for request %s: %s",
                attempt,
                max_attempts,
                request_id,
                validation_error,
            )
            if attempt == max_attempts:
                break
        except ClientError as exc:
            last_error = exc
            if _is_prepayment_depleted(exc) or _is_quota_or_billing_error(exc):
                logger.error(
                    "Busan Vertex Gemini quota or billing error for request %s: %s",
                    request_id,
                    exc,
                )
                raise BusanProviderQuotaError(
                    "Gemini 호출 권한/쿼터/결제 설정 문제로 부산 동화를 생성할 수 없습니다. "
                    "Google Cloud Vertex AI 프로젝트의 결제, API 사용 설정, 모델 쿼터를 확인해 주세요."
                ) from exc
            logger.error(
                "Busan Vertex Gemini client error (attempt %s/%s) for request %s: %s",
                attempt,
                max_attempts,
                request_id,
                exc,
                exc_info=True,
            )
            if attempt == max_attempts:
                break
        except Exception as exc:
            last_error = exc
            logger.error(
                "Failed to generate busan story (attempt %s/%s) for request %s: %s",
                attempt,
                max_attempts,
                request_id,
                exc,
                exc_info=True,
            )
            if attempt == max_attempts:
                break

    if last_error is not None:
        raise last_error

    raise RuntimeError("Busan story generation failed for unknown reasons")
