# service/openai_client.py
from typing import Optional
from openai import OpenAI
from schemas import GenerateRequest, GenerateResponse, Moderation, StoryOutput
import json
import logging
import base64
from io import BytesIO
from textwrap import dedent
from PIL import Image

logger = logging.getLogger(__name__)

class OpenAIClient:
    def __init__(self, api_key: str):
        self.client = OpenAI(api_key=api_key)
        self._base_image_style = dedent(
            """
            Illustration style guide: minimalistic watercolor with soft pastel palette,
            simple shapes, gentle lighting, subtle textures, and limited background detail.
            Maintain consistent character proportions (round face, expressive large eyes, short limbs)
            and identical costume colors across every scene in the same story. Avoid clutter and stick to two
            or three key props.
            """
        ).strip()

    def build_prompt(self, req: GenerateRequest, retry_reason: Optional[str] = None) -> list:
        is_ko = (str(req.language).upper() == "KO")
        topics_str = ", ".join(req.topics)
        objectives_str = ", ".join(req.objectives)
        lang_label = "한국어" if is_ko else "영어"
        title_line = f'[제목] "{req.title}" (고정)' if req.title else "[제목] 미정(직접 생성)"

        # === 강화된 시스템 프롬프트 ===
        system_prompt = (
            "너는 4~8세 아동용 그림책 작가이자 예비교사다.\n"
            "- 폭력/공포/편견/노골적 표현 금지. 따뜻하고 쉬운 어휘 사용.\n"
            "- 권선징악은 사건 전개 속에 자연스럽게 드러나야 하며, 설교식 표현은 피할 것.\n"
            "- 아동 눈높이에 맞춘 공감·배려·용기·성장을 담되, 문장은 짧고 리듬감 있게.\n"
            "- 출력은 반드시 JSON 하나만. 추가 텍스트/설명/코드블록 금지."
        )

        # === 사용자 프롬프트 ===
        user_prompt = f"""
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
- 이야기 구조를 반드시 반영: [발단]→[전개]→[위기]→[절정]→[결말] 순서를 내부적으로 준수하되, 텍스트에는 라벨을 절대 표기하지 말 것.
- 분량은 시중 아동용 그림책처럼 **10~20페이지**를 권장한다. {req.min_pages}는 최소 기준이며, 가능한 한 풍부하게 확장할 것.
- 각 페이지는 2~3문장 분량으로 짧고 리듬감 있게 작성할 것.
- 주인공은 반드시 뚜렷한 성격(예: 호기심 많음, 용감함, 장난꾸러기 등)을 가져야 하며, 사건 전개와 해결 과정에서 성격이 드러나야 한다.
- 조력자/반대자/환경도 각각 개성이 드러나야 하며, 주인공과의 상호작용 속에서 성격이 자연스럽게 표현되어야 한다.
- 스토리텔링은 설명 중심이 아닌 **대화·행동·감정**을 통해 전개될 것.
- 최소 3회 이상 반복 구절(리프레인)을 사용하여 아이가 따라 말하거나 기억할 수 있게 한다. (예: "천천히 하면 돼. 하나, 둘, 셋!")
- 의성어·의태어(예: "사박사박", "쿵!")와 짧은 대화를 적절히 배치해 생동감을 줄 것.
- 동물·자연·사물이 말을 하거나 행동하는 **의인화·환상적 요소**를 적극 활용하되, 과도한 공포·폭력 묘사는 금지한다.
- 각 페이지 끝은 가벼운 궁금증이나 여운을 남겨 페이지 터닝을 유도한다.
- 마지막은 '교훈:' 같은 라벨 대신, 따뜻한 정서 문장으로 자연스럽게 교훈을 전달한다.
  (예: "토토는 오늘도 천천히, 씩씩하게 걸어갔어요. 작은 용기도 큰 힘이 된다는 걸 알았지요.")

# 형식/분량 규칙
- pages는 최소 {req.min_pages}개 이상, 권장 10~20개. page는 1부터 1씩 증가.
- quiz는 0~3개, options는 3개, a는 0부터 시작하는 정답 인덱스.
- 키/구조를 절대 바꾸지 말 것. JSON 외 다른 텍스트 금지.

# 형식 예시(참고용, 실제 내용은 달라야 함)
{{"story":{{"title":"숲 속 친구들",
  "pages":[
    {{"page":1,"text":"토토는 숫자가 어렵다고 느꼈어요. 미미가 손을 잡고 말했죠, \\"천천히 하면 돼.\\""}},
    {{"page":2,"text":"바람이 살랑—, 나뭇잎이 사박사박. \\"하나, 둘, 셋!\\" 토토는 따라 속삭였어요."}},
    {{"page":3,"text":"토토가 서두르다 틀렸어요. 콩— 마음이 철렁! 미미가 웃었죠, \\"다시 해 보자.\\""}},
    {{"page":4,"text":"깊게 숨 쉬고, 천천히. \\"천천히 하면 돼. 하나, 둘, 셋!\\" 토토의 눈이 반짝였어요."}},
    {{"page":5,"text":"이젠 스스로 셀 수 있어요. 토토는 미미와 손을 흔들었죠. 오늘도 천천히, 씩씩하게."}}
  ],
  "quiz":[{{"q":"토토가 다시 해볼 수 있었던 이유는?","options":["빨리 해서","친구가 도와줘서","운이 좋아서"],"a":1}}]
}}}}
""".strip()

        if retry_reason:
            user_prompt += (
                f"\n\n[재시도 사유] {retry_reason}\n"
                "- 구조 순서는 내부적으로 지키되, 텍스트에 라벨 표기 금지.\n"
                "- 마지막은 설교식 표현이 아닌 따뜻한 정서 문장으로 마무리.\n"
                "동일 스키마(JSON)로 다시 출력."
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

    def generate_image(self, text: str, request_id: str, style_hint: Optional[str] = None) -> str:
        prompt = dedent(
            f"""
            {self._base_image_style}
            If previous pages exist, reuse the same character design and color palette.
            Scene description: {text}
            {style_hint or ''}
            """
        ).strip()

        logger.info(f"Generating image for request_id {request_id} with prompt: {prompt}")

        response = self.client.images.generate(
            model="gpt-image-1",
            prompt=prompt,
            size="1024x1024",
            quality="standard",
            n=1,
            response_format="b64_json"
        )

        raw_b64 = response.data[0].b64_json
        image_bytes = base64.b64decode(raw_b64)

        img = Image.open(BytesIO(image_bytes)).convert("RGB")
        img = img.resize((512, 512), Image.LANCZOS)
        buffer = BytesIO()
        img.save(buffer, format="JPEG", quality=70, optimize=True)
        compressed_b64 = base64.b64encode(buffer.getvalue()).decode("utf-8")

        return compressed_b64

    def create_tts(self, text: str, voice: str = "alloy") -> bytes:
        logger.info(f"Generating TTS for text: {text[:30]}...")
        response = self.client.audio.speech.create(
            model="tts-1",
            voice=voice,
            input=text,
            response_format="mp3"
        )
        return response.read()
