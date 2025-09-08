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

        # === 강화된 시스템 프롬프트 ===
        system_prompt = (
            "너는 4~8세 아동용 교육 동화 작가이자 예비교사다.\n"
            "폭력/공포/편견/노골적 표현 금지. 따뜻하고 쉬운 어휘 사용.\n"
            "권선징악(바른 선택은 보상받고, 잘못된 행동은 안전하고 교육적인 선에서 결과를 겪음)을 분명히 드러내라.\n"
            "아동 눈높이에 맞춰 공감·배려·용기·성장을 담아라.\n"
            "출력은 반드시 JSON 하나만. 추가 텍스트/설명/코드블록 금지."
        )

        topics_str = ", ".join(req.topics)
        objectives_str = ", ".join(req.objectives)
        lang_label = "한국어" if is_ko else "영어"
        title_line = f'[제목] "{req.title}" (고정)' if req.title else "[제목] 미정(직접 생성)"

        # === 사용자 프롬프트: 스키마는 유지, 내용 규칙만 강화 ===
        #  - 구조 라벨을 text 앞에 삽입: [발단]→[전개]→[위기]→[절정]→[결말]
        #  - 페이지 수가 부족하면 구조를 압축, 많으면 배분 (순서 고정)
        #  - 마지막 페이지 text 끝에 '교훈: ...' 한 줄 포함
        #  - 권선징악을 사건 전개/해결에 반영
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
            "pages": [{{"page": 1, "text": "string"}}],
            "quiz": [{{"q": "string", "options": ["string","string","string"], "a": 0}}]
          }}
        }}

        # 스토리텔링 요구사항
        - 이야기 구조를 반드시 반영: [발단]→[전개]→[위기]→[절정]→[결말] 라벨을 각 page의 text 맨 앞에 붙여라.
          - 페이지 수가 {req.min_pages} 이상이더라도, 구조 순서는 반드시 유지.
          - 페이지 수가 5보다 적으면 구조를 압축하여 순서를 지키되 한 페이지에 2개 라벨을 병기할 수 있음(예: "[전개·위기] ...").
          - 페이지 수가 5 이상이면 각 구조에 최소 1페이지 이상을 배분.
        - 등장요소: 주인공 이름, 조력자(또는 친구/가족), 갈등요소(오해/실수/규칙 위반 등), 배경(언제/어디), 해결 과정.
        - 권선징악을 분명히 표현: 잘못은 안전하게 교정되고, 올바른 선택과 배려는 보상받는다.
        - 마지막 페이지 text의 끝에는 '교훈: ~' 형태로 한 문장을 반드시 추가(예: "교훈: 작은 용기도 큰 변화를 만든다.").
        - 어휘는 4~8세에게 자연스럽고 따뜻하게. 문장은 짧고 리듬감 있게.

        # 형식/분량 규칙
        - pages는 최소 {req.min_pages}개, page는 1부터 1씩 증가.
        - 각 text는 2~3문장 권장(길이는 엄격히 제한하지 않음, 이후 자바에서 보정).
        - quiz는 0~3개, options는 3개, a는 0부터 시작하는 정답 인덱스.
        - 키/구조를 절대 바꾸지 말 것. JSON 외 다른 텍스트 금지.

        # 형식 예시(참고용, 내용은 예시일 뿐) 실제 내용에는 [발단] 등의 나누는 문구는 적지 않을것. 
        {{"story":{{"title":"숲 속 친구들",
          "pages":[
            {{"page":1,"text":"[발단] 토토는 숫자를 어려워했어요. 친구 미미가 함께 연습해 보자고 했지요."}},
            {{"page":2,"text":"[전개] 둘은 나뭇잎을 하나둘셋 세어 보았어요. 토토는 조금씩 자신감을 얻었지요."}},
            {{"page":3,"text":"[위기] 서두르다 보니 잘못 세고 말았어요. 토토는 포기하고 싶었어요."}},
            {{"page":4,"text":"[절정] 미미가 '천천히 다시 해 보자'고 말했어요. 토토는 깊게 숨 쉬고 또다시 셌어요."}},
            {{"page":5,"text":"[결말] 드디어 정확히 셀 수 있었어요! 교훈: 실수해도 천천히 다시 하면 돼요."}}
          ],
          "quiz":[{{"q":"숫자를 셀 때 중요한 태도는?","options":["빨리 하기","천천히 정확히","아예 안 하기"],"a":1}}]
        }}}}
        """).strip()

        if retry_reason:
            user_prompt += (
                f"\n\n[재시도 사유] {retry_reason}\n"
                "- 구조 라벨([발단][전개][위기][절정][결말])을 page text 맨 앞에 반드시 포함.\n"
                "- 마지막 페이지 text 끝에 '교훈:' 한 줄을 반드시 포함.\n"
                "스키마를 정확히 지켜 같은 형식으로 다시 출력."
            )

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

    def generate_image(self, text: str, request_id: str) -> str:
        prompt = f"A children's storybook illustration for the following scene: {text}"
        logger.info(f"Generating image for request_id {request_id} with prompt: {prompt}")

        response = self.client.images.generate(
            model="dall-e-3",
            prompt=prompt,
            size="1024x1024",
            quality="standard",
            n=1,
            response_format="b64_json"
        )

        return response.data[0].b64_json