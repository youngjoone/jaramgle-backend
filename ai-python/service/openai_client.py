# service/openai_client.py
from openai import OpenAI
from schemas import GenerateRequest, GenerateResponse, Moderation, StoryPage, StoryOutput, QA
from typing import List, Dict, Optional, Any
import json
import logging

logger = logging.getLogger(__name__)

class OpenAIClient:
    def __init__(self, api_key: str):
        self.client = OpenAI(api_key=api_key)

    def build_prompt(self, req: GenerateRequest, retry_reason: Optional[str] = None) -> list:
        system_prompt = (
            "너는 4~8세 아동용 교육 동화 작가이자 예비교사다.\n"
            "폭력/공포/편견/노골적 표현 금지. 따뜻하고 쉬운 어휘 사용.\n"
            "연령대에 맞게 짧은 문장과 쉬운 단어를 선택한다.\n"
            "출력은 반드시 JSON 형식이어야 한다. 다른 텍스트는 포함하지 않는다."
        )

        user_prompt_template = (
            "[연령대] {age_range}세\n"
            "[주제] {topics}\n"
            "[목표] {objectives}\n"
            "[최소 페이지] {min_pages} (각 페이지 90~150자, 2~3문장, 한 줄)\n"
            "[언어] {language}\n"
            "\n"
            "요구하는 JSON 출력 형식:\n"
            "{{\n"
        )

        user_prompt = user_prompt_template.format(
            age_range=req.age_range,
            topics=req.topics, # Assuming topics is already a list of strings
            objectives=req.objectives, # Assuming objectives is already a list of strings
            min_pages=req.min_pages,
            language=req.language
        )

        if retry_reason:
            user_prompt += f"\n\n이전 출력이 JSON 스키마에 맞지 않았습니다: {retry_reason}. 요구한 스키마에 정확히 맞춰 다시 출력하세요."

        return [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ]

    def validate_story_output(self, story_output: StoryOutput, req: GenerateRequest) -> bool:
        # Temporarily disable validation for debugging
        return True

    def generate_text(self, req: GenerateRequest, request_id: str) -> GenerateResponse:
        messages = self.build_prompt(req)
        raw_json_output = ""
        moderation = Moderation() # Default safe

        try:
            resp = self.client.chat.completions.create(
                model="gpt-4o-mini",
                messages=messages,
                temperature=0.7,
                max_tokens=1000,
                user=request_id or "anon",
                response_format={"type":"json_object"}
            )
            raw_json_output = resp.choices[0].message.content.strip()
            logger.info(f"LLM raw output: {raw_json_output}")

            # Attempt to parse JSON
            data = json.loads(raw_json_output)
            generated_story = StoryOutput(**data)

            return GenerateResponse(story=generated_story, raw_json=raw_json_output, moderation=moderation)

        except Exception as e:
            logger.error(f"LLM call or parsing failed: {e}", exc_info=True)
            # Return a dummy response or re-raise a more specific exception
            # For debugging, we'll re-raise to see the full stack trace
            raise ValueError(f"GENERATION_ERROR: {e}")