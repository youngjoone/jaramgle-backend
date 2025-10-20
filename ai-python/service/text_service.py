
import os
import json
import logging
from textwrap import dedent
from typing import Any, Dict, Optional

import google.generativeai as genai
from openai import OpenAI

from config import Config
from schemas import GenerateRequest, GenerateResponse, StoryOutput, CreativeConcept, Moderation

logger = logging.getLogger(__name__)

def _normalize_and_validate_story(story: StoryOutput, req: GenerateRequest) -> StoryOutput:
    """Ensure the story meets structural requirements and normalise page text."""
    min_pages = req.min_pages or 10
    page_count = len(story.pages)
    if page_count < min_pages:
        raise ValueError(f"LLM generated {page_count} pages but at least {min_pages} pages are required.")

    min_words_per_page = 20
    for idx, page in enumerate(story.pages, start=1):
        clean_text = " ".join(page.text.split())
        page.text = clean_text
        word_count = len(clean_text.split())
        if word_count < min_words_per_page:
            raise ValueError(f"Page {idx} has {word_count} words; at least {min_words_per_page} words are required per page.")
        if not getattr(page, "image_prompt", None):
            summary = clean_text[:140].rstrip()
            page.image_prompt = summary + ("..." if len(clean_text) > 140 else "")
    return story



def _build_gemini_story_prompt(req: GenerateRequest) -> str:
    """Gemini에 전달할 동화 생성 전용 프롬프트를 생성합니다."""
    is_ko = str(req.language).upper() == "KO"
    topics_str = ", ".join(req.topics)
    objectives_str = ", ".join(req.objectives)
    lang_label = "한국어" if is_ko else "영어"
    title_line = f'[제목] "{req.title}" (고정)' if req.title else "[제목] 미정(직접 생성)"
    min_pages = req.min_pages or 10
    moral_line = (req.moral or "").strip()
    art_style_pref = (req.art_style or "").strip()
    required_items = [item.strip() for item in req.required_elements if item.strip()]

    character_lines = []
    if req.characters:
        for character in req.characters:
            details = []
            if character.persona:
                details.append(f"성격: {character.persona}")
            if character.catchphrase:
                details.append(f"말버릇: {character.catchphrase}")
            prompt_keywords = getattr(character, "prompt_keywords", None)
            if prompt_keywords:
                details.append(f"시각 키워드: {prompt_keywords}")
            detail_text = " | ".join(details) if details else "(추가 설명 없음)"
            slug_hint = f" ({character.slug})" if character.slug else ""
            character_lines.append(f"- {character.name}{slug_hint}: {detail_text}")
    if character_lines:
        characters_section = "\n".join(character_lines)
    else:
        characters_section = "- (선택된 캐릭터 없음)"

    goal_section = f"""
- 주제 키워드: {topics_str or '자유 선택'}
- 학습 목표: {objectives_str or '자유 선택'}
- 전달하고 싶은 교훈: {moral_line or '이야기 흐름 속에서 자연스럽게 긍정적인 메시지를 드러낸다.'}
- 설교식 문장 대신 사건 전개와 대사를 통해 메시지를 자연스럽게 전달한다."""

    if art_style_pref:
        art_direction_intro = (
            f'- 사용자 지정 그림 스타일: "{art_style_pref}" 콘셉트를 중심으로 한다.\n'
            "  • creative_concept.art_style도 동일 문구로 서술하고, page.image_prompt마다 일관되게 반영한다."
        )
    else:
        art_direction_intro = "- 사용자 지정 그림 스타일: 기본 동화책용 수채화/파스텔 톤을 유지한다."

    art_direction_points = f"""
{art_direction_intro}
- creative_concept.mood_and_tone을 이야기 전체와 모든 image_prompt에 일관되게 반영한다.
- Realistic/photorealistic 표현은 피하고, 아동용 동화책 일러스트 톤을 유지한다.
- 장면마다 색감·소품·배경 디테일을 활용해 따뜻하고 상상력 넘치는 분위기를 만든다."""

    page_rules_points = f"""
- pages 배열에는 **정확히 {min_pages}개**의 항목만 포함되며, 그 밖의 텍스트는 반환하지 않는다.
- 각 페이지는 독립적인 장면을 다루고, page.text는 한 단락(20단어 이상)을 유지한다.
- page.image_prompt는 1~2문장으로 장면 배경·핵심 캐릭터·감정을 묘사하고, 텍스트 오버레이나 말풍선을 넣지 않는다."""

    action_rules_points = """
- page.text에는 주요 인물의 행동·감정·상호작용을 명시적으로 묘사한다.
- page.image_prompt에는 캐릭터의 자세·표정·행동과 함께 배경 환경의 변화(날씨, 시간대, 주변 사물)를 구체적으로 포함한다.
- 반복적인 정적 장면을 피하고, 이야기 흐름이 자연스럽게 이어지도록 장면 간 변화를 설계한다.
- 아래 필수 요소(있다면)는 이야기 텍스트와 image_prompt 양쪽에 자연스럽게 등장시킨다."""

    character_continuity_points = """
- 각 캐릭터의 이름·역할·관계를 일관되게 유지하고, 페이지마다 성격이 잘 드러나도록 한다.
- 의상과 소품은 character_sheets 또는 요청 정보에서 제시한 분위기를 반영한다.
- 새로운 캐릭터를 도입할 경우 character_sheets에 이름, slug(케밥 케이스 권장), visual_description, voice_profile을 반드시 추가한다."""

    if required_items:
        required_elements_section = "\n".join(f"- {item}" for item in required_items)
    else:
        required_elements_section = "- (명시된 필수 요소 없음. 이야기 전개에 자연스럽게 필요한 소재를 활용한다.)"

    full_prompt = f"""
너는 4~8세 아동용 그림책 작가이자 아트 디렉터다.
- 글과 그림 아이디어를 동시에 고려하여 JSON 하나로 결과를 만든다.
- 폭력/공포/편견/노골적 표현은 제외하고, 따뜻하고 쉬운 어휘를 사용한다.
- 출력은 반드시 JSON 하나만 제공하며 추가 텍스트나 설명을 붙이지 않는다.

[입력 요약]
- 연령대: {req.age_range}세
- 언어: {lang_label}
- {title_line}

[목표 & 톤]
{goal_section}

[주요 캐릭터]
{characters_section}
- 필요하다면 새 캐릭터를 도입해도 된다. 다만 등장한 캐릭터는 모두 `character_sheets`에 정의해야 한다.

[필수 등장 요소]
{required_elements_section}
- 위 요소는 page.text와 page.image_prompt 모두에서 자연스럽게 활용한다.

[비주얼 디렉션]
{art_direction_points}

[페이지 구성]
{page_rules_points}

[장면 연출]
{action_rules_points}

[캐릭터 일관성]
{character_continuity_points}

# 출력 스키마 (키 고정, 추가 키 금지)
{{
  "creative_concept": {{
    "art_style": "string (A unified art style guide for all illustrations.)",
    "mood_and_tone": "string (The overall mood and tone for the story, art, and audio.)",
    "character_sheets": [{{ "name": "string", "slug": "string", "visual_description": "string", "voice_profile": "string" }}]
  }},
  "story_outline": [{{ ... }}],
  "story": {{
    "title": "string",
    "pages": [{{
      "page": 1,
      "text": "string",
      "image_prompt": "string (1~2 sentence illustration brief: layout, key characters, background, lighting, consistent art style)."
    }}],
    "quiz": [{{ "q": "string", "options": ["string","string","string"], "a": 0}}]
  }}
}}

# 작성 지침
1. `creative_concept`, `story_outline`, `story`, `quiz`가 모두 채워져 있고 pages 배열 길이가 정확히 {min_pages}인지 확인한다.
2. 각 page.text는 한 단락·20단어 이상이며, page.image_prompt는 1~2문장으로 텍스트 오버레이 없이 장면을 묘사하는지 점검한다.
3. 등장한 모든 캐릭터가 `character_sheets`에 name, slug, visual_description, voice_profile과 함께 정의되어 있는지 확인한다.
4. 최종 출력은 JSON 한 개뿐인지 다시 점검한다.
"""
    return dedent(full_prompt).strip()


def _call_gemini(req: GenerateRequest, request_id: str) -> Dict[str, Any]:
    """Gemini API를 호출하고 결과를 dict로 반환합니다."""
    client = genai.GenerativeModel(
        model_name="models/gemini-2.5-flash",
        generation_config={"response_mime_type": "application/json"}
    )
    prompt = _build_gemini_story_prompt(req)
    
    logger.info(f"Calling Gemini for request_id: {request_id}")
    response = client.generate_content(
        prompt,
        generation_config=genai.types.GenerationConfig(temperature=0.7),
    )
    
    raw_json_text = response.text
    logger.info(f"Gemini raw response for {request_id}: {raw_json_text}")
    return json.loads(raw_json_text)

def _call_openai(req: GenerateRequest, request_id: str) -> Dict[str, Any]:
    """OpenAI API를 호출하고 결과를 dict로 반환합니다."""
    # TODO: OpenAI 프롬프트도 reading_plan을 포함하도록 수정 필요
    logger.warning("OpenAI client in text_service is not fully implemented with reading_plan.")
    client = OpenAI(api_key=Config.OPENAI_API_KEY)
    # ... (기존 openai_client.py의 build_prompt 및 API 호출 로직) ...
    # 임시로 빈 리스트 반환
    return {"story": {"title": "temp", "pages": [], "quiz": []}, "reading_plan": []}

def generate_story(req: GenerateRequest, request_id: str) -> GenerateResponse:
    """
    설정에 따라 적절한 LLM을 호출하여 스토리를 생성합니다.
    """
    provider = Config.LLM_PROVIDER.lower()
    max_attempts = 3
    last_error: Optional[Exception] = None

    for attempt in range(1, max_attempts + 1):
        try:
            if provider == 'gemini':
                story_data = _call_gemini(req, request_id)
            elif provider == 'openai':
                story_data = _call_openai(req, request_id)
            else:
                raise ValueError(f"Unsupported LLM provider: {provider}")

            story = StoryOutput(**story_data["story"])
            story = _normalize_and_validate_story(story, req)
            concept_data = story_data.get("creative_concept")
            concept = CreativeConcept(**concept_data) if concept_data else None
            raw_json = json.dumps(story_data, ensure_ascii=False)

            return GenerateResponse(
                story=story,
                creative_concept=concept,
                reading_plan=[],
                raw_json=raw_json,
                moderation=Moderation(safe=True)
            )
        except ValueError as validation_error:
            last_error = validation_error
            logger.warning(
                "Story validation failed (attempt %s/%s) for request %s: %s",
                attempt,
                max_attempts,
                request_id,
                validation_error,
            )
        except Exception as exc:
            last_error = exc
            logger.error(
                "Failed to generate story (attempt %s/%s) for request %s: %s",
                attempt,
                max_attempts,
                request_id,
                exc,
                exc_info=True,
            )
            break

    if last_error is not None:
        raise last_error

    raise RuntimeError("Story generation failed for unknown reasons")
