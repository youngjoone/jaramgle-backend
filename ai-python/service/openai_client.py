# service/openai_client.py
from typing import Optional
from openai import OpenAI
from schemas import GenerateRequest, GenerateResponse, Moderation, StoryOutput
import json
import logging
from textwrap import dedent

logger = logging.getLogger(__name__)

class OpenAIClient:
    def __init__(self, api_key: str):
        self.client = OpenAI(api_key=api_key)

    def build_prompt(self, req: GenerateRequest, retry_reason: Optional[str] = None) -> list:
        is_ko = (str(req.language).upper() == "KO")

        system_prompt = (
            "너는 4~8세 아동용 교육 동화 작가이자 예비교사다.\n"
            "폭력/공포/편견/노골적 표현 금지. 따뜻하고 쉬운 어휘 사용.\n"
            "출력은 반드시 JSON 하나만. 추가 텍스트/설명/코드블록 금지."
        )

        topics_str = ", ".join(req.topics)
        objectives_str = ", ".join(req.objectives)
        lang_label = "한국어" if is_ko else "영어"
        title_line = f'[제목] "{req.title}" (고정)' if req.title else "[제목] 미정(직접 생성)"

        user_prompt = dedent(f"""
        [연령대] {req.age_range}세
        [주제] {topics_str}
        [학습목표] {objectives_str}
        [언어] {lang_label}
        {title_line}

        # 출력 스키마(키 고정, 추가 키 금지)
        {{
          "story": {{
            "title": "string",
            "pages": [{{ "page": 1, "text": "string" }}],
            "quiz": [{{ "q": "string", "options": ["string","string","string"], "a": 0 }}]
          }}
        }}

        # 작성 규칙
        - pages는 최소 {req.min_pages}개, page는 1부터 1씩 증가.
        - 각 text는 2~3문장 권장(길이는 엄격히 제한하지 않음, 이후 자바에서 보정).
        - quiz는 0~3개, options는 3개, a는 0부터 시작하는 정답 인덱스.
        - 키/구조를 절대 바꾸지 말 것. JSON 외 다른 텍스트 금지.

        # 형식 예시(참고용)
        {{"story":{{"title":"숲 속 친구들","pages":[{{"page":1,"text":"하나, 둘, 셋! 숫자 놀이를 시작해요."}}],"quiz":[{{"q":"첫 숫자는?","options":["하나","둘","셋"],"a":0}}]}}}}
        """).strip()

        if retry_reason:
            user_prompt += f"\n\n[재시도 사유] {retry_reason}\n스키마를 정확히 지켜 같은 형식으로 다시 출력."

        return [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ]

    def _normalize(self, data: dict) -> dict:
        """모델이 흔들린 키를 스키마 키(page/q/a)로 통일"""
        story = data.get("story", data)

        # pages: page_no -> page
        for p in story.get("pages", []):
            if "page" not in p and "page_no" in p:
                p["page"] = p.pop("page_no")

        # quiz: question/answer -> q/a
        for q in story.get("quiz", []):
            if "q" not in q and "question" in q:
                q["q"] = q.pop("question")
            if "a" not in q and "answer" in q:
                q["a"] = q.pop("answer")

        if "quiz" not in story or story["quiz"] is None:
            story["quiz"] = []

        return {"story": story}

    def generate_text(self, req: GenerateRequest, request_id: str) -> GenerateResponse:
        moderation = Moderation()
        messages = self.build_prompt(req)

        resp = self.client.chat.completions.create(
            model="gpt-4o-mini",
            messages=messages,
            temperature=0.7,
            max_tokens=1000,
            user=request_id or "anon",
            response_format={"type": "json_object"},
        )

        raw = resp.choices[0].message.content.strip()
        logger.info(f"LLM raw output: {raw}")

        story_data = json.loads(raw)
        story_data = self._normalize(story_data)

        # Pydantic 검증(스키마 준수 확인)
        story = StoryOutput(**story_data["story"])

        return GenerateResponse(
            story=story,
            raw_json=json.dumps(story_data, ensure_ascii=False),
            moderation=moderation,
        )
