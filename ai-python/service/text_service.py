
import os
import json
import logging
from textwrap import dedent
from typing import Any, Dict, Optional

import google.generativeai as genai
from openai import OpenAI

from config import Config
from schemas import (
    GenerateRequest,
    GenerateResponse,
    StoryOutput,
    CreativeConcept,
    Moderation,
    TranslationOutput,
)

logger = logging.getLogger(__name__)

def _normalize_and_validate_story(story: StoryOutput, req: GenerateRequest) -> StoryOutput:
    """Ensure the story meets structural requirements and normalise page text."""
    min_pages = req.min_pages or 10
    page_count = len(story.pages)
    if page_count < min_pages:
        raise ValueError(f"LLM generated {page_count} pages but at least {min_pages} pages are required.")

    # Validate quiz: must have exactly 3 items, each with 3 options and a valid answer index
    quizzes = getattr(story, "quiz", []) or []
    if len(quizzes) != 3:
        raise ValueError(f"LLM returned {len(quizzes)} quizzes; exactly 3 are required.")
    for idx, quiz in enumerate(quizzes, start=1):
        # QA model exposes `question`/`answer` (aliases q/a). Use canonical names.
        if not quiz.question or not quiz.options or quiz.answer is None:
            raise ValueError(f"Quiz {idx} is missing required fields.")
        if len(quiz.options) != 3:
            raise ValueError(f"Quiz {idx} has {len(quiz.options)} options; exactly 3 options required.")
        if any(not opt for opt in quiz.options):
            raise ValueError(f"Quiz {idx} contains empty options.")
        if quiz.answer < 0 or quiz.answer >= len(quiz.options):
            raise ValueError(f"Quiz {idx} has invalid answer index {quiz.answer}.")

    # 언어별 최소 길이 기준: 공백 토크나이징이 어려운 CJK는 문자 수로 검증
    lang_code = str(req.language).upper()
    min_words_per_page = 20
    min_chars_per_page = 40  # KO/JA/ZH일 때 적용

    def _length_score(text: str) -> tuple[int, str]:
        if lang_code in {"KO", "JA", "ZH"}:
            chars = len("".join(text.split()))
            return chars, "chars"
        return len(text.split()), "words"

    for idx, page in enumerate(story.pages, start=1):
        clean_text = " ".join(page.text.split())
        page.text = clean_text
        length, unit = _length_score(clean_text)
        threshold = min_chars_per_page if unit == "chars" else min_words_per_page
        if length < threshold:
            raise ValueError(
                f"Page {idx} has {length} {unit}; at least {threshold} {unit} are required per page."
            )
        if not getattr(page, "image_prompt", None):
            summary = clean_text[:140].rstrip()
            page.image_prompt = summary + ("..." if len(clean_text) > 140 else "")
    return story



def _build_gemini_story_prompt(req: GenerateRequest) -> str:
    """Gemini에 전달할 동화 생성 전용 프롬프트를 생성합니다."""
    # Language mapping
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
    lang_label = lang_map.get(lang_code, "한국어") # Default to Korean if unknown

    topics_str = ", ".join(req.topics)
    objectives_str = ", ".join(req.objectives)
    title_line = f'[제목] "{req.title}" (고정)' if req.title else "[제목] 미정(직접 생성)"
    min_pages = req.min_pages or 10
    moral_line = (req.moral or "").strip()
    art_style_pref = (req.art_style or "").strip()
    required_items = [item.strip() for item in req.required_elements if item.strip()]

    character_lines = []
    user_selected_names = []

    if req.characters:
        for character in req.characters:
            # 사용자가 선택한 캐릭터는 모두 주인공급으로 대우
            role_desc = "**(주요 인물 - 사용자가 선택함, 이야기의 중심)**"
            user_selected_names.append(character.name)

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
            character_lines.append(f"- {character.name}{slug_hint} {role_desc}: {detail_text}")

    existing_character_count = len(req.characters or [])
    max_total_characters = 3
    additional_allowed = max(0, max_total_characters - existing_character_count)

    if character_lines:
        characters_section = "\n".join(character_lines)
    else:
        characters_section = "- (선택된 캐릭터 없음, AI가 주인공을 새로 창조해야 함)"

    if additional_allowed == 0:
        additional_character_rule = "- 현재 인물 구성만 사용하며, 새로운 캐릭터는 추가하지 않는다."
    else:
        additional_character_rule = (
            f"- 새로운 캐릭터는 최대 {additional_allowed}명까지만 추가할 수 있다.\n"
            "- **중요**: 새로 추가된 캐릭터는 절대 '주요 인물'의 비중을 넘을 수 없으며, 단순히 그들을 돕거나 스쳐가는 '단역/조연'에 머물러야 한다."
        )

    goal_section = f"""
- 주제 키워드: {topics_str or '자유 선택'}
- 학습 목표: {objectives_str or '자유 선택'}
- 전달하고 싶은 교훈: {moral_line or '이야기 흐름 속에서 자연스럽게 긍정적인 메시지를 드러낸다.'}
- 설교식 문장 대신 사건 전개와 대사를 통해 메시지를 자연스럽게 전달한다."""

    if art_style_pref:
        art_direction_intro = (
            f'- 이번 동화의 통일된 그림 스타일은 "{art_style_pref}" 콘셉트로 한다. (동일 동화 내에서는 완전히 일관되게 유지)\n'
            "  • creative_concept.art_style에 정확히 동일한 문구를 적고, page.image_prompt마다 같은 팔레트/붓터치/촬영감으로 반복한다.\n"
            "  • 개별 장면에서 창의적으로 연출해도 되지만, 캐릭터 외형/채도/윤곽선은 모든 페이지에서 동일해야 한다."
        )
    else:
        art_direction_intro = (
            "- 이번 동화의 기본 그림 스타일: 따뜻한 파스텔 수채화 + 은은한 텍스처. (한 편 안에서는 톤과 라인 두께를 고정한다.)"
        )

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

    character_continuity_points = f"""
- **이야기의 핵심은 '사용자가 선택한 주요 캐릭터들({", ".join(user_selected_names) if user_selected_names else "없음"})'이 이끌어가야 한다.**
- AI가 임의로 생성한 추가 캐릭터가 이야기의 해결사가 되거나 주인공보다 더 큰 비중을 차지해서는 안 된다.
- 각 캐릭터의 이름·역할·관계를 일관되게 유지하고, 페이지마다 성격이 잘 드러나도록 한다.
- 동일한 이름을 가진 새로운 캐릭터를 추가하지 말고, 기존 캐릭터의 이름/slug를 그대로 사용한다.
- 의상과 소품은 character_sheets 또는 요청 정보에서 제시한 분위기를 반영한다.
- 새 캐릭터를 만들 때는 다양한 범주의 친구(예: 동물, 사람, 로봇, 요정, 악기, 자동차·비행기 등 의인화된 사물)를 고르게 조합하고, 특정 종이나 유형이 반복되지 않도록 한다.
- 새로운 캐릭터를 도입할 경우 character_sheets에 이름, slug(케밥 케이스 권장), visual_description, voice_profile을 반드시 추가한다."""

    if required_items:
        required_elements_section = "\n".join(f"- {item}" for item in required_items)
    else:
        required_elements_section = "- (명시된 필수 요소 없음. 이야기 전개에 자연스럽게 필요한 소재를 활용한다.)"

    logger.info(f"Building prompt for language: {lang_code} -> {lang_label}")

    full_prompt = f"""
너는 4~8세 아동용 그림책 작가이자 아트 디렉터다.
- 글과 그림 아이디어를 동시에 고려하여 JSON 하나로 결과를 만든다.
- 폭력/공포/편견/노골적 표현은 제외하고, 따뜻하고 쉬운 어휘를 사용한다.
- 출력은 반드시 JSON 하나만 제공하며 추가 텍스트나 설명을 붙이지 않는다.

[입력 요약]
- 연령대: {req.age_range}세
- 언어: {lang_label}
- {title_line}

[언어 규칙 (중요)]
- **이야기 본문(story.pages.text), 제목(story.title), 퀴즈(story.quiz)는 반드시 '{lang_label}'로 작성한다.**
- 프롬프트의 지시어가 한국어라 하더라도, 최종 결과물(JSON 값)은 반드시 요청된 언어('{lang_label}')로만 작성해야 한다.

[목표 & 톤]
{goal_section}

[주요 캐릭터]
{characters_section}
- 위 목록의 캐릭터는 이름과 slug를 그대로 사용하고, 동일 인물을 중복 생성하지 않는다.
- 새로운 캐릭터가 정말 필요하다면 기존 이름·slug와 겹치지 않는 완전히 새로운 값을 사용하고, 반드시 `character_sheets`에 정의한다.
{additional_character_rule}

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

[퀴즈 제작]
- 퀴즈는 **정확히 3개** 생성한다.
  1) 스토리 이해형: 누가/무엇을/어디서/왜/어떻게 등 이야기 내용에 대한 사실 확인 질문.
  2) 어휘 의미형: 이야기 속에 실제로 등장한 단어·표현의 의미나 역할을 묻는 질문(정답·오답 보기 모두 이야기에서 나온 요소만 사용).
  3) 메시지/감상형: 교훈, 인물의 선택 이유, 다음 행동 예측 등 이야기의 메시지나 감정에 대한 질문.
- 각 문항은 3지선다이며, 보기 3개 모두 스토리 맥락과 일치해야 하고 서로 중복되면 안 된다.
- 정답 인덱스(a)는 0~2 중 하나이며, 3개 문항 모두 다른 정답 위치를 섞어 배치한다.
- 질문과 보기 표현은 요청 언어('{lang_label}')로, 연령대에 맞게 짧고 쉬운 어휘를 사용한다.

# 출력 스키마 (키 고정, 추가 키 금지)
{{
  "creative_concept": {{
    "art_style": "string (A unified art style guide for all illustrations.)",
    "mood_and_tone": "string (The overall mood and tone for the story, art, and audio.)",
    "character_sheets": [{{ "name": "string", "slug": "string", "visual_description": "string", "voice_profile": "string" }}]
  }},
  "story_outline": [{{ ... }}],
  "story": {{
    "title": "string (Must be in {lang_label})",
    "pages": [{{
      "page": 1,
      "text": "string (Must be in {lang_label})",
      "image_prompt": "string (1~2 sentence illustration brief: layout, key characters, background, lighting, consistent art style)."
    }}],
    "quiz": [{{ "question": "string", "options": ["string","string","string"], "answer": 0}}]
  }}
}}

# 작성 지침
1. `creative_concept`, `story_outline`, `story`, `quiz`가 모두 채워져 있고 pages 배열 길이가 정확히 {min_pages}인지 확인한다.
2. **가장 중요**: story.title과 story.pages.text가 요청된 언어('{lang_label}')로 작성되었는지 확인한다.
3. 각 page.text는 한 단락·20단어 이상이며, page.image_prompt는 1~2문장으로 텍스트 오버레이 없이 장면을 묘사하는지 점검한다.
4. 등장한 모든 캐릭터가 `character_sheets`에 name, slug, visual_description, voice_profile과 함께 정의되어 있는지 확인한다.
5. 최종 출력은 JSON 한 개뿐인지 다시 점검한다.
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


def _translate_story(story: StoryOutput, source_lang: str, target_lang: str, request_id: str) -> Optional[TranslationOutput]:
    """
    Translate story.title and pages.text into target language using Gemini.
    """
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
    Translate the given story from {source_label} to {target_label}.
    Keep sentences warm, clear, and age-appropriate (4-8 years old).
    Do not add explanations.
    Return ONLY JSON with keys: "title" and "pages" (array of objects: "page", "text").

    Original story (title then pages):
    [Title] {story.title}
    {pages_block}
    """).strip()

    client = genai.GenerativeModel(
        model_name="models/gemini-2.5-flash",
        generation_config={"response_mime_type": "application/json"}
    )
    logger.info(f"Translating story for request {request_id}: {source}->{target}")
    response = client.generate_content(
        prompt,
        generation_config=genai.types.GenerationConfig(temperature=0.4),
    )
    raw_json_text = response.text
    logger.info(f"Translation raw response for {request_id}: {raw_json_text}")
    data = json.loads(raw_json_text)
    return TranslationOutput(**data)

def generate_story(req: GenerateRequest, request_id: str) -> GenerateResponse:
    """
    설정에 따라 적절한 LLM을 호출하여 스토리를 생성합니다.
    """
    provider = Config.LLM_PROVIDER.lower()
    max_attempts = 2  # 최초 1회 + 재시도 1회
    last_error: Optional[Exception] = None
    translation: Optional[TranslationOutput] = None

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

            # Optional translation
            if req.translation_language:
                try:
                    translation = _translate_story(story, req.language, req.translation_language, request_id)
                except Exception as translate_err:
                    logger.warning("Translation failed for request %s: %s", request_id, translate_err)
                    translation = None

            return GenerateResponse(
                story=story,
                creative_concept=concept,
                reading_plan=[],
                translation=translation,
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
            if attempt == max_attempts:
                break
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
            if attempt == max_attempts:
                break

    if last_error is not None:
        raise last_error

    raise RuntimeError("Story generation failed for unknown reasons")
