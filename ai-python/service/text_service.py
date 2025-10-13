
import os
import json
import logging
from textwrap import dedent
from typing import Dict, Optional

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
            character_lines.append(f"- {character.name} ({character.slug}): {detail_text}")
    if character_lines:
        characters_section = "[선택된 캐릭터 가이드]\n" + "\n".join(character_lines)
    else:
        characters_section = "[선택된 캐릭터 가이드]\n- (선택된 캐릭터 없음)"

    topic_goal_section = f"""[주제·학습목표 가이드]
- 주제 키워드: {topics_str or '자유 선택'}
- 학습 목표: {objectives_str or '자유 선택'}
- 설교식 문장 대신 사건 전개와 대사를 통해 자연스럽게 드러낼 것."""

    art_direction_section = """[일관된 아트 디렉션]
- creative_concept.art_style과 mood_and_tone은 이야기 전체와 모든 image_prompt에서 동일하게 유지한다.
- Realistic/photorealistic 표현은 피하고, 아동용 동화책 일러스트 톤을 유지한다."""

    page_rules_section = f"""[페이지 구성 규칙]
- 최소 {min_pages}개의 페이지를 작성하며, 각 페이지는 독립적인 장면을 다룬다.
- 각 page.text는 한 단락(최소 20단어)으로 작성하고, 불필요한 줄바꿈을 넣지 않는다.
- 각 page.image_prompt는 1~2문장으로 장면의 배경·주요 캐릭터·감정을 요약하고, 위의 아트 디렉션을 다시 상기시킨다."""

    full_prompt = f"""
너는 4~8세 아동용 그림책 작가이자, 아트 디렉터다.
- 너의 임무는 글과 그림 아이디어를 포함하는 통합적인 창작물을 JSON 하나로 생성하는 것이다.
- 폭력/공포/편견/노골적 표현 금지. 따뜻하고 쉬운 어휘 사용.
- 출력은 반드시 JSON 하나만. 추가 텍스트/설명/코드블록 금지.

[요청 정보]
- 연령대: {req.age_range}세
- 언어: {lang_label}
- {title_line}

{topic_goal_section}

{characters_section}
- 위에 명시된 캐릭터 외 새로운 인물을 만들지 말 것.

{art_direction_section}

{page_rules_section}

# 출력 스키마 (키 고정, 추가 키 금지)
{{
  "creative_concept": {{
    "art_style": "string (A unified art style guide for all illustrations.)",
    "mood_and_tone": "string (The overall mood and tone for the story, art, and audio.)",
    "character_sheets": [{{ "name": "string", "visual_description": "string", "voice_profile": "string" }}]
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
1.  `creative_concept`, `story_outline`, `story`를 모두 포함하는 동화를 구상한다.
2.  동화의 각 `pages`에 들어가는 `text`는 **최소 20단어 이상**으로 충분히 작성하되 한 단락으로 유지한다.
3.  각 페이지마다 `image_prompt`를 1~2문장으로 작성하되 `creative_concept.art_style`과 `mood_and_tone`, 그리고 위에서 정의한 캐릭터 가이드를 참고해 일관된 그림 콘셉트를 유지한다. (장면 구성, 배경, 주요 행동, 감정 묘사를 포함)
4.  모든 내용을 종합하여 단일 JSON 객체로 최종 출력한다.
"""
    return dedent(full_prompt).strip()


def _call_gemini(req: GenerateRequest, request_id: str) -> Dict:
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

def _call_openai(req: GenerateRequest, request_id: str) -> Dict:
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
            concept = CreativeConcept(**story_data.get("creative_concept", {}))
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
