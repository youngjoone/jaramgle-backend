import json
import logging
from textwrap import dedent
from typing import Any, Dict, Optional

import google.generativeai as genai

from config import Config
from schemas import (
    GenerateRequest,
    GenerateResponse,
    StoryOutput,
    CreativeConcept,
    Moderation,
    TranslationOutput,
)
from service.text_service import _normalize_and_validate_story, _translate_story

logger = logging.getLogger(__name__)


def _detect_busan_mission(req: GenerateRequest) -> str:
    signal = " ".join([
        *(req.topics or []),
        *(req.objectives or []),
        req.moral or "",
        *(req.required_elements or []),
    ]).lower()
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
        """).strip(),
        "HERITAGE": dedent("""
        - 부산 공식 캐릭터 '부기'가 문화유산 탐험의 안내자 역할을 맡는다.
        - 제공된 장소/설명 기반 사실 정보를 최소 1개 이상 포함한다.
        - 연도/고유명사 등 확신 없는 사실은 단정하지 말고 일반 설명으로 완화한다.
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
    art_style = (req.art_style or "맑고 따뜻한 해양 동화 일러스트").strip()
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
    client = genai.GenerativeModel(
        model_name="models/gemini-2.5-flash",
        generation_config={"response_mime_type": "application/json"},
    )
    prompt = _build_busan_prompt(req)
    logger.info("Calling Busan Gemini for request_id: %s", request_id)
    response = client.generate_content(
        prompt,
        generation_config=genai.types.GenerationConfig(temperature=0.65),
    )

    raw_json_text = response.text
    logger.info("Busan Gemini raw response for %s: %s", request_id, raw_json_text)
    try:
        return json.loads(raw_json_text)
    except json.JSONDecodeError:
        from json_repair import repair_json

        repaired = repair_json(raw_json_text)
        logger.warning("Busan story JSON decode failed. Attempting repair for request %s", request_id)
        return json.loads(repaired)


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

            story = StoryOutput(**story_data["story"])
            story = _normalize_and_validate_story(story, req)
            concept_data = story_data.get("creative_concept")
            concept = CreativeConcept(**concept_data) if concept_data else None
            raw_json = json.dumps(story_data, ensure_ascii=False)

            if req.translation_language:
                try:
                    translation = _translate_story(story, req.language, req.translation_language, request_id)
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
