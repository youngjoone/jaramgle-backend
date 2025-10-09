
import os
import json
import logging
from textwrap import dedent
from typing import Dict

import google.generativeai as genai
from openai import OpenAI

from config import Config
from schemas import GenerateRequest, GenerateResponse, StoryOutput, CreativeConcept, Moderation

logger = logging.getLogger(__name__)


def _build_gemini_prompt_with_plan(req: GenerateRequest) -> str:
    """Gemini에 전달할 통합 프롬프트 (오디오 계획 포함)를 생성합니다."""
    is_ko = str(req.language).upper() == "KO"
    topics_str = ", ".join(req.topics)
    objectives_str = ", ".join(req.objectives)
    lang_label = "한국어" if is_ko else "영어"
    title_line = f'[제목] "{req.title}" (고정)' if req.title else "[제목] 미정(직접 생성)"
    min_pages = req.min_pages or 10

    # ### NEW: reading_plan 스키마를 프롬프트에 추가
    full_prompt = f"""
너는 4~8세 아동용 그림책 작가이자, 아트 디렉터, 오디오북 연출가다.
- 너의 임무는 글, 그림, 음성, 오디오 연출 계획까지 아우르는 통합적인 창작물을 JSON 하나로 생성하는 것이다.
- 폭력/공포/편견/노골적 표현 금지. 따뜻하고 쉬운 어휘 사용.
- 출력은 반드시 JSON 하나만. 추가 텍스트/설명/코드블록 금지.

[요청 정보]
- 연령대: {req.age_range}세
- 주제: {topics_str}
- 학습목표: {objectives_str}
- 언어: {lang_label}
- {title_line}

# 출력 스키마 (키 고정, 추가 키 금지)
{{
  "creative_concept": {{ ... }},
  "story_outline": [{{ ... }}],
  "story": {{
    "title": "string",
    "pages": [{{ "page": 1, "text": "string" }}],
    "quiz": [{{ "q": "string", "options": ["string","string","string"], "a": 0}}]
  }},
  "reading_plan": [
    {{
      "segment_type": "string (narration 또는 dialogue)",
      "speaker": "string (narrator 또는 캐릭터 slug)",
      "emotion": "string (TTS를 위한 감정/말투 가이드)",
      "text": "string (실제 읽을 문장)"
    }}
  ]
}}

# 작성 지침
1.  먼저 `creative_concept`, `story_outline`, `story`를 모두 포함하는 동화를 완성한다.
2.  완성된 `story`의 `pages`를 기반으로, 전체 이야기를 자연스럽게 낭독하기 위한 `reading_plan`을 생성한다.
3.  `reading_plan`은 전체 텍스트를 빠짐없이 포함해야 하며, 각 문장을 적절한 단위로 나누고 `segment_type`, `speaker`, `emotion`, `text` 필드를 채운다.
4.  이 모든 것을 포함하는 단일 JSON 객체를 최종적으로 출력한다.
"""
    return dedent(full_prompt).strip()

def _call_gemini(req: GenerateRequest, request_id: str) -> Dict:
    """Gemini API를 호출하고 결과를 dict로 반환합니다."""
    client = genai.GenerativeModel(
        model_name="models/gemini-2.5-flash",
        generation_config={"response_mime_type": "application/json"}
    )
    prompt = _build_gemini_prompt_with_plan(req)
    
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

def generate_story_with_plan(req: GenerateRequest, request_id: str) -> GenerateResponse:
    """
    설정에 따라 적절한 LLM을 호출하여 스토리와 오디오 계획을 생성합니다.
    """
    story_data = {}
    provider = Config.LLM_PROVIDER.lower()

    try:
        if provider == 'gemini':
            story_data = _call_gemini(req, request_id)
        elif provider == 'openai':
            story_data = _call_openai(req, request_id)
        else:
            raise ValueError(f"Unsupported LLM provider: {provider}")

        # Pydantic 모델로 변환
        story = StoryOutput(**story_data["story"])
        concept = CreativeConcept(**story_data.get("creative_concept", {}))
        reading_plan = story_data.get("reading_plan", [])
        raw_json = json.dumps(story_data, ensure_ascii=False)

        return GenerateResponse(
            story=story,
            creative_concept=concept,
            reading_plan=reading_plan,
            raw_json=raw_json,
            moderation=Moderation(safe=True)
        )
    except Exception as e:
        logger.error(f"Failed to generate story with plan for request {request_id}: {e}", exc_info=True)
        raise
