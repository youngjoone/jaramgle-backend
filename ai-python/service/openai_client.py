# service/openai_client.py
from openai import OpenAI
from schemas import GenerateRequest, GenerateResponse, Moderation, StoryPage, StoryOutput, QA
from typing import List, Dict, Optional, Any
import json
import logging
from textwrap import dedent

logger = logging.getLogger(__name__)

PAGE_MIN = 90
PAGE_MAX = 150

class OpenAIClient:
    def __init__(self, api_key: str):
        self.client = OpenAI(api_key=api_key)

    def build_prompt(self, req: GenerateRequest, retry_reason: Optional[str] = None) -> list:
        # 언어 토글
        is_ko = (str(req.language).upper() == "KO")

        system_prompt = (
            "너는 4~8세 아동용 교육 동화 작가이자 예비교사다.\n"
            "폭력/공포/편견/노골적 표현 금지. 따뜻하고 쉬운 어휘 사용.\n"
            "연령대에 맞게 짧은 문장과 쉬운 단어를 선택한다.\n"
            "출력은 반드시 JSON 형식이며, 다른 텍스트는 포함하지 않는다."
        )

        # LLM이 ‘정확한 키’와 ‘길이’를 지키도록 강제
        guide = dedent(f"""
        요구사항:
        - 최소 페이지 수: {req.min_pages}
        - 각 페이지는 한 줄(개행 금지)로 작성
        - 각 페이지는 2~3문장
        - 각 페이지 길이: {PAGE_MIN}~{PAGE_MAX}자(공백 포함, 한국어 기준)
        - 키 이름을 정확히 사용: pages[].page, pages[].text, quiz[].q, quiz[].options, quiz[].a
        - options는 3개 제공, a는 정답의 0-based index
        - JSON 외 불필요한 텍스트 출력 금지

        출력 JSON 스키마 예시:
        {{
          "story": {{
            "title": "<제목>",
            "pages": [{{"page": 1, "text": "<110~140자 문장>"}}, ...],
            "quiz": [{{"q": "<문제>", "options": ["A","B","C"], "a": 0}}]
          }}
        }}
        """)

        # 사용자 입력 요약
        topics_str = ", ".join(req.topics)
        objectives_str = ", ".join(req.objectives)
        lang_label = "한국어" if is_ko else "영어"

        user_prompt = dedent(f"""
        [연령대] {req.age_range}세
        [주제] {topics_str}
        [학습목표] {objectives_str}
        [언어] {lang_label}
        [스타일] 따뜻함, 안전함, 또래 어휘

        {guide}
        """)

        if retry_reason:
            user_prompt += f"\n재작성 사유: {retry_reason}\n요구 스키마와 길이 조건을 반드시 충족해 다시 출력하세요."

        return [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ]

    def _check_lengths(self, story_dict: Dict[str, Any]) -> List[Dict[str, Any]]:
        """길이 벗어난 페이지 목록 반환: [{'page': n, 'len': x, 'text': '...'}]"""
        bad = []
        for p in story_dict["story"]["pages"]:
            text = p.get("text", "")
            n = len(text)
            if n < PAGE_MIN or n > PAGE_MAX:
                bad.append({"page": p.get("page") or p.get("page_no"), "len": n, "text": text})
        return bad

    def _rewrite_bad_pages(self, req: GenerateRequest, story_dict: Dict[str, Any], bad_pages: List[Dict[str, Any]]) -> Dict[str, Any]:
        """문제 페이지들만 1회 재작성해서 교체"""
        # 재작성 프롬프트: 바꾸려는 페이지만 반환하도록
        system_prompt = "너는 아동용 동화 문장을 규격에 맞게 다듬는 에디터다. 출력은 JSON만."
        want = {
            "title": story_dict["story"]["title"],
            "pages": story_dict["story"]["pages"],
            "need_fix": [{"page": b["page"], "text": b["text"]} for b in bad_pages]
        }
        user = dedent(f"""
        아래 동화에서 길이 조건({PAGE_MIN}~{PAGE_MAX}자, 2~3문장, 개행 금지)에 맞지 않는 페이지만 고쳐줘.
        의미와 맥락은 유지하되, 같은 페이지 번호를 사용해 대체문만 반환해.

        요구 JSON:
        {{
          "replacements": [{{"page": <번호>, "text": "<수정문>"}}, ...]
        }}

        원문:
        {json.dumps(want, ensure_ascii=False)}
        """)

        resp = self.client.chat.completions.create(
            model="gpt-4o-mini",
            response_format={"type": "json_object"},
            temperature=0.7,
            max_tokens=700,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user},
            ],
        )
        data = json.loads(resp.choices[0].message.content.strip())
        repls = {r["page"]: r["text"] for r in data.get("replacements", [])}

        # 교체
        for p in story_dict["story"]["pages"]:
            pg = p.get("page") or p.get("page_no")
            if pg in repls:
                p["text"] = repls[pg]
        return story_dict

    def generate_text(self, req: GenerateRequest, request_id: str) -> GenerateResponse:
        moderation = Moderation()  # 기본 안전
        # 1차 생성
        messages = self.build_prompt(req)
        resp = self.client.chat.completions.create(
            model="gpt-4o-mini",
            messages=messages,
            temperature=0.7,
            max_tokens=1300,
            user=request_id or "anon",
            response_format={"type": "json_object"},
        )
        raw_json_output = resp.choices[0].message.content.strip()
        logger.info(f"LLM raw output: {raw_json_output}")

        # JSON 파싱
        story_data = json.loads(raw_json_output)

        # 키 정규화: page_no로 왔든 page로 왔든 통일해서 처리
        for p in story_data["story"]["pages"]:
            if "page" not in p and "page_no" in p:
                p["page"] = p.pop("page_no")

        # 길이 점검
        bad = self._check_lengths(story_data)
        if bad:
            logger.warning(f"Length out of range on first pass: {bad}")
            # 1회 재작성
            story_data = self._rewrite_bad_pages(req, story_data, bad)
            bad2 = self._check_lengths(story_data)
            if bad2:
                # 여전히 실패 → 어떤 페이지가 몇 자였는지 400으로 명확히 반환
                details = ", ".join([f"p{b['page']}={b['len']}" for b in bad2])
                raise ValueError(f"PAGE_LEN_OUT_OF_RANGE_AFTER_REWRITE: {details}")

        # Pydantic 검증
        generated_story = StoryOutput(**story_data["story"])

        # 최종 응답
        return GenerateResponse(
            story=generated_story,
            raw_json=json.dumps(story_data, ensure_ascii=False),
            moderation=moderation,
        )
