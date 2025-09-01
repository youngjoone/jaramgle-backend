# service/openai_client.py
from openai import OpenAI
from schemas import GenerateRequest, GenerateResponse, Moderation, StoryPage, StoryOutput, QA
from typing import List, Dict, Optional, Any
import json # Import json module

class OpenAIClient:
    def __init__(self, api_key: str):
        self.client = OpenAI(api_key=api_key)

    def build_prompt(self, req: GenerateRequest, retry_reason: Optional[str] = None) -> list: # Added retry_reason
        system_prompt = (
            "너는 4~8세 아동용 교육 동화 작가이자 예비교사다.\n"
            "폭력/공포/편견/노골적 표현 금지. 따뜻하고 쉬운 어휘 사용.\n"
            "연령대에 맞게 짧은 문장과 쉬운 단어를 선택한다."
        )

        user_prompt_template = (
            "[연령대] {age_range}세\n"
            "[주제] {topics}\n"
            "[목표] {objectives}\n"
            "[최소 페이지] {min_pages} (각 페이지는 80~120자 내외)\n"
            "[언어] {language}\n"
            "\n"
            "[출력형식(JSON). 다른 텍스트 금지]\n"
            "{\n"
            "  \"title\": \"string\",\n"
            "  \"pages\": [ { \"pageNo\": 1, \"text\": \"...\" } ],\n"
            "  \"quiz\":  [ { \"q\": \"...\", \"a\": \"...\" } ]\n"
            "}"
        )

        user_prompt = user_prompt_template.format(
            age_range=req.age_range,
            topics=req.topics, # Assuming topics is already a list of strings
            objectives=req.objectives, # Assuming objectives is already a list of strings
            min_pages=req.min_pages,
            language=req.language
        )

        if retry_reason: # Add retry reason to user prompt
            user_prompt += f"\n\n이전 출력이 JSON 스키마에 맞지 않았습니다: {retry_reason}. 요구한 스키마에 정확히 맞춰 다시 출력하세요."

        return [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ]

    def validate_story_output(self, story_output: StoryOutput, req: GenerateRequest) -> bool:
        if not story_output.title or not story_output.pages:
            return False
        if len(story_output.pages) < req.min_pages:
            return False
        for page in story_output.pages:
            if not page.text or not (80 <= len(page.text) <= 120): # Approximate length check
                return False
        return True

    def generate_text(self, req: GenerateRequest, request_id: str) -> GenerateResponse:
        messages = self.build_prompt(req)
        raw_json_output = ""
        generated_story = None
        moderation = Moderation() # Default safe

        # First attempt
        try:
            resp = self.client.chat.completions.create(
                model="gpt-4o-mini",
                messages=messages,
                temperature=0.7,
                max_tokens=1000,
                user=request_id or "anon",
                response_format={"type":"json_object"} # Attempt JSON object
            )
            raw_json_output = resp.choices[0].message.content.strip()
            generated_story = StoryOutput.parse_raw(raw_json_output) # Attempt to parse
            if not self.validate_story_output(generated_story, req):
                raise ValueError("Initial LLM output failed validation.")

        except (json.JSONDecodeError, ValueError) as e: # Catch JSON parsing or validation errors
            # Retry once with reason injected
            retry_reason = f"JSON 파싱 또는 유효성 검사 실패: {str(e)[:100]}..." # Truncate reason
            messages = self.build_prompt(req, retry_reason)
            try:
                resp = self.client.chat.completions.create(
                    model="gpt-4o-mini",
                    messages=messages,
                    temperature=0.7,
                    max_tokens=1000,
                    user=request_id or "anon",
                    response_format={"type":"json_object"} # Attempt JSON object again
                )
                raw_json_output = resp.choices[0].message.content.strip()
                generated_story = StoryOutput.parse_raw(raw_json_output)
                if not self.validate_story_output(generated_story, req):
                    raise ValueError("Retry LLM output failed validation.")
            except (json.JSONDecodeError, ValueError) as retry_e:
                raise ValueError(f"MODEL_OUTPUT_INVALID: LLM output invalid after retry: {str(retry_e)}")
        except Exception as e: # Catch other LLM call errors
            raise ValueError(f"LLM_CALL_FAILED: Failed to call LLM service: {str(e)}")

        return GenerateResponse(story=generated_story, raw_json=raw_json_output, moderation=moderation)