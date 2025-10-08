import os
import json
from textwrap import dedent
from typing import Optional, List

# google-generativeai 라이브러리를 임포트합니다.
import google.generativeai as genai

# 기존 프로젝트의 스키마는 재사용합니다.
from schemas import GenerateRequest, CharacterProfile

# --- openai_client.py에서 프롬프트 생성 로직 일부를 가져와 단순화 ---
# 이 함수는 이 파일 내에서만 사용되는 독립적인 함수입니다.
def build_gemini_prompt(req: GenerateRequest) -> str:
    is_ko = str(req.language).upper() == "KO"
    topics_str = ", ".join(req.topics)
    objectives_str = ", ".join(req.objectives)
    lang_label = "한국어" if is_ko else "영어"
    title_line = f'[제목] "{req.title}" (고정)' if req.title else "[제목] 미정(직접 생성)"
    min_pages = 10

    # 시스템 프롬프트와 유저 프롬프트를 결합하여 Gemini에 맞는 단일 프롬프트로 구성
    # (OpenAI와 달리 보통 시스템 메시지를 분리하지 않고 컨텍스트에 함께 제공)
    full_prompt = f"""
너는 4~8세 아동용 그림책 작가이자 아트 디렉터다.
- 너의 임무는 글, 그림, 음성까지 아우르는 통합적인 창작 콘셉트를 먼저 정의하고, 그에 맞춰 스토리를 쓰는 것이다.
- 폭력/공포/편견/노골적 표현 금지. 따뜻하고 쉬운 어휘 사용.
- 아동 눈높이에 맞춘 공감·배려·용기·성장을 담되, 문장은 짧고 리듬감 있게.
- 출력은 반드시 JSON 하나만. 추가 텍스트/설명/코드블록 금지.

[요청 정보]
- 연령대: {req.age_range}세
- 주제: {topics_str}
- 학습목표: {objectives_str}
- 언어: {lang_label}
- {title_line}

# 출력 스키마 (키 고정, 추가 키 금지)
{{
  "creative_concept": {{
    "art_style": "string (A unified art style guide for all illustrations)",
    "mood_and_tone": "string (The overall mood for the story, art, and audio)",
    "character_sheets": [
      {{
        "name": "string (Character's name)",
        "visual_description": "string (Detailed visual description for the image generation AI to ensure consistency)",
        "voice_profile": "string (Voice tone and emotion guide for the TTS AI)"
      }}
    ]
  }},
  "story_outline": [{{"page": 1, "summary": "string"}}],
  "story": {{
    "title": "string",
    "pages": [{{"page": 1, "text": "string"}}],
    "quiz": [{{"q": "string", "options": ["string","string","string"], "a": 0}}]
  }}
}}

# 작성 지침
- `creative_concept`과 `story`를 모두 포함하는 단일 JSON 객체를 생성하라.
- 이야기는 [발단]→[전개]→[위기]→[절정]→[결말] 구조를 따르라.
- 분량은 최소 {min_pages} 페이지 이상으로 하라.
- 각 페이지(`pages`)의 `text`는 약 100단어 이상으로 풍부하게 작성할 것.
- `story_outline` 배열에 모든 페이지의 요약을 포함하라.
- 키/구조를 절대 바꾸지 말고, JSON 외 다른 텍스트는 절대 출력하지 마라.
"""
    return dedent(full_prompt).strip()


def run_gemini_test():
    """Gemini 텍스트 생성을 테스트하는 메인 함수"""
    print("--- Gemini 텍스트 생성 테스트 시작 ---")

    # 1. API 키 설정
    api_key = os.getenv("GEMINI_API_KEY")
    if not api_key:
        print("오류: GEMINI_API_KEY 환경 변수를 설정해주세요.")
        return

    genai.configure(api_key=api_key)

    # 2. 테스트용 요청 데이터 생성 (기존 GenerateRequest 스키마 활용)
    sample_request = GenerateRequest(
        language="KO",
        age_range="5-7",
        topics=["우정", "모험"],
        objectives=["협동의 중요성 배우기"],
        min_pages=10, # 이 필드가 누락되었었습니다.
        characters=[
            CharacterProfile(id=1, slug="toto-rabbit", name="토토", persona="용감한 토끼"),
            CharacterProfile(id=2, slug="coco-bear", name="코코", persona="신중한 곰"),
        ]
    )
    print(f"요청 데이터: {sample_request.model_dump()}")

    # 3. Gemini 모델 및 프롬프트 준비
    # JSON 출력을 위해 gemini-1.5-flash 모델 사용
    model = genai.GenerativeModel(
        model_name="models/gemini-2.5-flash",
        generation_config={"response_mime_type": "application/json"}
    )
    prompt = build_gemini_prompt(sample_request)
    print("\n--- 생성된 프롬프트 (일부) ---")
    print(prompt[:500] + "...")
    print("--------------------------\n")


    # 4. Gemini API 호출
    try:
        print("Gemini API 호출 중...")
        response = model.generate_content(prompt)

        # 5. 결과 처리 및 출력
        print("--- Gemini 응답 결과 ---")
        # Pydantic 모델 등으로 검증하기 전의 순수 텍스트/JSON 결과
        raw_json_text = response.text
        
        # 예쁘게 포맷팅하여 출력
        parsed_json = json.loads(raw_json_text)
        pretty_json = json.dumps(parsed_json, indent=2, ensure_ascii=False)
        print(pretty_json)

    except Exception as e:
        print(f"\n오류 발생: {e}")
        # API 응답에 오류 세부 정보가 포함된 경우 출력
        if hasattr(e, 'response'):
            print(f"API 응답: {e.response}")

    print("\n--- 테스트 종료 ---")


if __name__ == "__main__":
    run_gemini_test()
