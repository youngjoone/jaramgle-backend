from html import escape
import re
from typing import Dict, List, Optional, Tuple
import random

from openai import OpenAI

from config import Config
from schemas import (
    CharacterProfile,
    CharacterVisual,
    CreativeConcept,
    GenerateAudioRequest,
    GenerateRequest,
    GenerateResponse,
    Moderation,
    StoryOutput,
)

import base64
import json
import logging
import wave
from io import BytesIO
from textwrap import dedent

from PIL import Image

from service.azure_tts_client import AzureTTSClient, AzureTTSConfigurationError
from service.image_providers import (
    GeminiImageProvider,
    GeminiProviderConfig,
    ImageProvider,
    ImageProviderError,
    OpenAIImageProvider,
    OpenAIProviderConfig,
)

logger = logging.getLogger(__name__)

SUPPORTED_OPENAI_VOICES = {"alloy", "ash", "coral", "fable", "onyx", "sage", "echo", "nova", "shimmer"}

VOICE_PRESETS: Dict[str, dict] = {
    "narration": {
        "voice": "alloy",
        "style": "따뜻하고 차분한 엄마 목소리로",
        "azure_voice": "ko-KR-SunHiNeural",
        "azure_style": "friendly",
        "azure_styledegree": "1.0",
        "azure_rate": "0%",
    },
    "default": {
        "voice": "alloy",
        "style": "자연스럽고 편안하게",
        "azure_voice": "ko-KR-SunHiNeural",
        "azure_style": "calm",
        "azure_styledegree": "1.0",
        "azure_rate": "0%",
    },
    "characters": {
        "lulu-rabbit": {
            "voice": "coral",
            "style": "발랄하고 귀엽게",
            "azure_voice": "ko-KR-SunHiNeural",
            "azure_style": "cheerful",
            "azure_styledegree": "1.2",
            "azure_rate": "+10%",
        },
        "mungchi-puppy": {
            "voice": "nova",
            "style": "친근하고 즐겁게",
            "azure_voice": "ko-KR-SoonBokNeural",
            "azure_style": "friendly",
            "azure_styledegree": "1.1",
            "azure_rate": "+6%",
        },
        "coco-squirrel": {
            "voice": "echo",
            "style": "빠르고 신나게",
            "azure_voice": "ko-KR-SeoHyeonNeural",
            "azure_style": "excited",
            "azure_styledegree": "1.2",
            "azure_rate": "+12%",
        },
        "ria-princess": {
            "voice": "shimmer",
            "style": "우아하고 상냥하게",
            "azure_voice": "ko-KR-SeoHyeonNeural",
            "azure_style": "gentle",
            "azure_styledegree": "1.1",
            "azure_rate": "0%",
        },
        "lucas-prince": {
            "voice": "fable",
            "style": "장난기 넘치고 씩씩하게",
            "azure_voice": "ko-KR-SunHiNeural",
            "azure_style": "cheerful",
            "azure_styledegree": "1.1",
            "azure_rate": "+8%",
        },
        "geo-explorer": {
            "voice": "ash",
            "style": "차분하지만 용감하게",
            "azure_voice": "ko-KR-SoonBokNeural",
            "azure_style": "calm",
            "azure_styledegree": "1.0",
            "azure_rate": "0%",
        },
        "robo-roro": {
            "voice": "onyx",
            "style": "기계적이면서 따뜻하게",
            "azure_voice": "ko-KR-SoonBokNeural",
            "azure_style": "calm",
            "azure_styledegree": "1.0",
            "azure_rate": "-4%",
        },
        "mimi-fairy": {
            "voice": "sage",
            "style": "속삭이듯 다정하게",
            "azure_voice": "ko-KR-SeoHyeonNeural",
            "azure_style": "whispering",
            "azure_styledegree": "1.0",
            "azure_rate": "-6%",
        },
        "pipi-math-monster": {
            "voice": "coral",
            "style": "경쾌하고 생동감 있게",
            "azure_voice": "ko-KR-SunHiNeural",
            "azure_style": "cheerful",
            "azure_styledegree": "1.1",
            "azure_rate": "+10%",
        },
        "nova-space": {
            "voice": "nova",
            "style": "꿈꾸듯 신비롭게",
            "azure_voice": "ko-KR-SunHiNeural",
            "azure_style": "hopeful",
            "azure_styledegree": "1.1",
            "azure_rate": "+4%",
        },
    },
}

NARRATIVE_TONES = [
    "따뜻하고 희망찬 모험",
    "재치 있고 경쾌한 탐험",
    "잔잔하고 감성적인 성장 이야기",
    "신비롭고 몽환적인 판타지",
    "활기차고 리듬감 있는 이야기",
]

SETTINGS = [
    "초록빛 숲속 마을",
    "반짝이는 바닷속 왕국",
    "별빛이 가득한 우주 정거장",
    "시간이 느리게 흐르는 마법 도서관",
    "친구들이 함께 사는 구름 위 마을",
]

WRITING_STYLES = [
    "리듬감을 살린 운율체",
    "따뜻한 나레이션 중심",
    "대사가 풍부한 연극체",
    "감각 묘사가 풍부한 묘사체",
    "아이와 대화하듯 다정한 구연체",
]

PACING_PATTERNS = [
    "초반은 천천히, 중반 이후 점차 속도를 올려 절정에서 가장 빠르게",
    "균등한 리듬 속에서 중요한 순간마다 살짝 호흡을 길게",
    "발단은 빠르게, 위기에서 긴장감을 충분히, 결말은 여유 있게 정리",
]

MORAL_THEMES = [
    "용기와 배려는 함께 자란다",
    "친구와 힘을 합치면 어려움을 이겨낼 수 있다",
    "작은 마음 씀씀이가 큰 기적을 만든다",
    "실수해도 다시 시도하면 더 성장할 수 있다",
    "서로를 존중하면 모두가 행복해진다",
]

STORY_PHASES = ("발단", "전개", "위기", "절정", "결말")
MIN_PAGE_WORDS = 120
MIN_LAST_PAGE_WORDS = 100


class OpenAIClient:
    def __init__(self, api_key: str):
        self.client = OpenAI(api_key=api_key)
        self.image_model = Config.OPENAI_IMAGE_MODEL
        self.image_size = Config.OPENAI_IMAGE_SIZE
        self.image_quality = Config.OPENAI_IMAGE_QUALITY
        self.reading_plan_model = Config.READING_PLAN_MODEL
        self._base_image_style = dedent(
            '''
            Illustration style guide: minimalistic watercolor with soft pastel palette,
            simple shapes, gentle lighting, subtle textures, and limited background detail.
            Maintain consistent character proportions (round face, expressive large eyes, short limbs)
            and identical costume colors across every scene in the same story. Avoid clutter and stick to two
            or three key props.
            '''
        ).strip()

        self._openai_image_provider = OpenAIImageProvider(
            client=self.client,
            config=OpenAIProviderConfig(
                model=self.image_model,
                size=self.image_size,
                quality=self.image_quality,
            ),
        )
        self._gemini_image_provider: Optional[ImageProvider] = None
        self._prefer_gemini = Config.USE_GEMINI_IMAGE

        if self._prefer_gemini:
            dimensions = self._parse_image_dimensions(self.image_size)
            try:
                self._gemini_image_provider = GeminiImageProvider(
                    api_key=Config.GEMINI_API_KEY,
                    config=GeminiProviderConfig(
                        model=Config.GEMINI_IMAGE_MODEL,
                        image_dimensions=dimensions,
                    ),
                )
                logger.info(
                    "Gemini image provider initialised (model=%s)",
                    Config.GEMINI_IMAGE_MODEL,
                )
            except ImageProviderError as exc:
                self._prefer_gemini = False
                logger.warning(
                    "Gemini image provider disabled, falling back to OpenAI: %s",
                    exc,
                )

        if not self._prefer_gemini:
            logger.info(
                "Using OpenAI image provider (model=%s)", self.image_model
            )

        self.azure_client: Optional[AzureTTSClient] = None
        logger.info("USE_AZURE_TTS=%s, region=%s", Config.USE_AZURE_TTS, Config.AZURE_SPEECH_REGION or "(unset)")
        if Config.USE_AZURE_TTS:
            try:
                self.azure_client = AzureTTSClient(
                    key=Config.AZURE_SPEECH_KEY,
                    region=Config.AZURE_SPEECH_REGION,
                    output_format=Config.AZURE_TTS_OUTPUT_FORMAT,
                )
                logger.info(
                    "Azure TTS client initialised for region %s",
                    Config.AZURE_SPEECH_REGION,
                )
            except AzureTTSConfigurationError as exc:
                logger.warning("Azure TTS disabled: %s", exc)
            except Exception:  # pragma: no cover - defensive safety log
                logger.exception("Failed to initialise Azure TTS client; falling back to OpenAI")

    # --------------------------------------------------------------------------------------
    # Story generation
    # --------------------------------------------------------------------------------------
    def _required_min_pages(self, req: GenerateRequest) -> int:
        requested_min = getattr(req, "min_pages", 10) or 10
        return max(int(requested_min), 10)

    @staticmethod
    def _count_sentences(text: str) -> int:
        """Best-effort sentence counter tolerant of quotes and missing punctuation."""
        pattern = re.compile(r"[^.!?]+[.!?]+[\"'”’]?", re.UNICODE)
        normalized = (text or "").replace("…", ".")
        matches = pattern.findall(normalized)
        consumed = pattern.sub("", normalized)

        count = len(matches)
        if consumed.strip():
            count += 1
        return count

    @staticmethod
    def _count_words(text: str) -> int:
        cleaned = (text or "").strip()
        if not cleaned:
            return 0
        tokens = re.findall(r"[\w\u3130-\u318F\uAC00-\uD7A3]+", cleaned)
        if not tokens:
            tokens = cleaned.split()
        return len(tokens)

    @staticmethod
    def _normalize_stage_label(value: Optional[str]) -> Optional[str]:
        if value is None:
            return None
        text = str(value).strip()
        if not text:
            return None
        match = re.search(r"(발단|전개|위기|절정|결말)", text)
        if match:
            return match.group(1)
        return None

    @staticmethod
    def _extract_stage(summary: str) -> Optional[str]:
        if not summary:
            return None
        label = OpenAIClient._normalize_stage_label(summary)
        if label:
            return label
        match = re.search(r"\((발단|전개|위기|절정|결말)\)\s*$", summary.strip())
        if match:
            return match.group(1)
        return None

    @staticmethod
    def _outline_summary(text: str) -> str:
        cleaned = (text or "").strip()
        if not cleaned:
            return ""
        sentences = re.split(r"(?<=[.!?…])\s+", cleaned)
        first = sentences[0] if sentences else cleaned
        first = first.strip()
        if len(first) > 120:
            return first[:117] + "…"
        return first

    def _parse_plaintext_story(self, raw: str) -> dict:
        if not raw or "## " not in raw:
            raise ValueError("Plaintext format markers missing")

        sections: Dict[str, str] = {}
        section_pattern = re.compile(r"^##\s*(.+)$", re.MULTILINE)
        matches = list(section_pattern.finditer(raw))
        if not matches:
            raise ValueError("No section headers found")

        for idx, match in enumerate(matches):
            key = match.group(1).strip().lower()
            start = match.end()
            end = matches[idx + 1].start() if idx + 1 < len(matches) else len(raw)
            sections[key] = raw[start:end].strip()

        concept_text = sections.get("creative concept")
        if not concept_text:
            raise ValueError("Creative concept section missing")

        def _extract_line(text_block: str, label: str) -> str:
            pattern = re.compile(rf"{label}\s*:\s*(.+)", re.IGNORECASE)
            match = pattern.search(text_block)
            if not match:
                raise ValueError(f"{label} not found")
            return match.group(1).strip()

        art_style = _extract_line(concept_text, "Art Style")
        mood_and_tone = _extract_line(concept_text, "Mood and Tone")

        character_sheets: List[Dict[str, str]] = []
        cs_match = re.search(r"Character Sheets:\s*(.*)", concept_text, re.IGNORECASE | re.DOTALL)
        if cs_match:
            cs_block = cs_match.group(1).strip()
            sheet_pattern = re.compile(
                r"(?:^|\n)\s*\d+\.\s*Name:\s*(.+?)\n\s*Visual Description:\s*(.+?)\n\s*Voice Profile:\s*(.+?)(?=\n\s*\d+\.|\Z)",
                re.IGNORECASE | re.DOTALL,
            )
            for sheet in sheet_pattern.finditer(cs_block):
                character_sheets.append(
                    {
                        "name": sheet.group(1).strip(),
                        "visual_description": sheet.group(2).strip(),
                        "voice_profile": sheet.group(3).strip(),
                    }
                )
        if not character_sheets:
            raise ValueError("Character sheets missing or malformed")

        outline_text = sections.get("story outline")
        if not outline_text:
            raise ValueError("Story outline section missing")

        outline_items: List[Dict[str, str]] = []
        outline_pattern = re.compile(
            r"^\s*(\d+)\.\s*Page\s*(\d+)\s*\(([^)]+)\):\s*(.+)$",
            re.IGNORECASE | re.MULTILINE,
        )
        for match in outline_pattern.finditer(outline_text):
            page_idx = int(match.group(2))
            summary = match.group(4).strip()
            phase = self._normalize_stage_label(match.group(3))
            entry = {"page": page_idx, "summary": summary}
            if phase:
                entry["phase"] = phase
            outline_items.append(entry)
        if not outline_items:
            raise ValueError("Story outline entries missing")

        story_text = sections.get("story")
        if not story_text:
            raise ValueError("Story section missing")

        story_title_match = re.search(r"Story Title:\s*(.+)", story_text, re.IGNORECASE)
        if story_title_match:
            story_title = story_title_match.group(1).strip()
        else:
            story_title = "제목 미정"

        pages_block = story_text[story_title_match.end():].strip() if story_title_match else story_text
        page_pattern = re.compile(
            r"Page\s+(?P<page>\d+)\s*\nTitle:\s*(?P<title>.+?)\nBody(?:\s*\(.*?\))?:\s*(?P<body>.+?)\nIllustration:\s*(?P<illustration>.+?)(?=\nPage\s+\d+|\Z)",
            re.DOTALL | re.IGNORECASE,
        )
        pages: List[Dict[str, str]] = []
        for match in page_pattern.finditer(pages_block):
            page_no = int(match.group("page"))
            title = match.group("title").strip()
            body = match.group("body").strip()
            illustration = match.group("illustration").strip()
            page_text = f"장면 제목: {title}\n{body}\n일러스트 묘사: {illustration}"
            pages.append({"page": page_no, "text": page_text})
        if not pages:
            raise ValueError("No story pages parsed")

        quiz: List[Dict[str, object]] = []
        quiz_key = next((key for key in sections.keys() if key.startswith("quiz")), None)
        if quiz_key:
            quiz_text = sections[quiz_key]
            question_pattern = re.compile(
                r"-\s*Question:\s*(.+)\n\s*Options:\s*\[(.+?)\]\s*\n\s*Answer:\s*(\d+)",
                re.IGNORECASE,
            )
            for match in question_pattern.finditer(quiz_text):
                options = [opt.strip() for opt in match.group(2).split(",")]
                quiz.append(
                    {
                        "q": match.group(1).strip(),
                        "options": options,
                        "a": int(match.group(3)),
                    }
                )

        story_data = {
            "creative_concept": {
                "art_style": art_style,
                "mood_and_tone": mood_and_tone,
                "character_sheets": character_sheets,
            },
            "story_outline": outline_items,
            "story": {
                "title": story_title,
                "pages": pages,
                "quiz": quiz,
            },
        }
        return story_data

    def build_prompt(self, req: GenerateRequest, retry_reason: Optional[str] = None) -> list:
        is_ko = str(req.language).upper() == "KO"
        topics_str = ", ".join(req.topics)
        objectives_str = ", ".join(req.objectives)
        lang_label = "한국어" if is_ko else "영어"
        title_line = f'[제목] "{req.title}" (고정)' if req.title else "[제목] 미정(직접 생성)"
        min_pages = self._required_min_pages(req)

        character_prompt_section = ""
        if getattr(req, "characters", None):
            character_details = []
            for idx, character in enumerate(req.characters, start=1):
                persona = character.persona or "성격 미지정"
                catchphrase = character.catchphrase or ""
                prompt_keywords = character.prompt_keywords or ""
                detail_lines = [f"{idx}. 이름: {character.name}"]
                detail_lines.append(f"   - 성격/역할: {persona}")
                if catchphrase:
                    detail_lines.append(f"   - 말버릇: {catchphrase}")
                if prompt_keywords:
                    detail_lines.append(f"   - 이미지 키워드: {prompt_keywords}")
                character_details.append("\n".join(detail_lines))
            character_prompt_section = (
                "\n[등장인물 가이드]\n"
                + "\n".join(character_details)
                + "\n- 선택된 캐릭터의 말투, 행동, 감정 표현을 이야기 전반에 자연스럽게 반영할 것."
                + "\n- 각 캐릭터의 말버릇이나 반복 구절을 최소 1회 이상 등장시켜 리듬감을 유지할 것."
                + "\n- 페이지마다 캐릭터가 교대로 활약하거나 협력하여 사건을 해결하는 장면을 포함할 것."
            )

        system_prompt = (
            "너는 4~8세 아동용 그림책 작가이자 아트 디렉터, 오디오북 연출가다.\n"
            "- 너의 임무는 글, 그림, 음성까지 아우르는 통합적인 창작 콘셉트를 먼저 정의하고, 그에 맞춰 스토리를 쓰는 것이다.\n"
            "- 폭력/공포/편견/노골적 표현 금지. 따뜻하고 쉬운 어휘 사용.\n"
            "- 권선징악은 사건 전개 속에 자연스럽게 드러나야 하며, 설교식 표현은 피할 것.\n"
            "- 아동 눈높이에 맞춘 공감·배려·용기·성장을 담되, 문장은 짧고 리듬감 있게.\n"
            "- 출력은 위에서 제시한 Markdown 포맷을 정확히 따른다. 불필요한 설명이나 여분의 텍스트를 덧붙이지 않는다."
        )

        user_prompt = f"""
[연령대] {req.age_range}세
[주제] {topics_str}
[학습목표] {objectives_str}
[언어] {lang_label}
{title_line}
{character_prompt_section}

## Creative Concept
Art Style: …
Mood and Tone: …
Character Sheets:
1. Name: …
   Visual Description: …
   Voice Profile: …

## Story Outline
1. Page 1 (발단): …
2. Page 2 (발단): …
3. Page 3 (전개): …
(이후 페이지도 같은 형식으로 {min_pages}페이지까지 이어집니다.)

## Story
Story Title: …
Page 1
Title: …
Body (min 120 words):
…
Illustration: …

Page 2
Title: …
Body (min 120 words):
…
Illustration: …
(Page {min_pages}까지 동일 형식 반복. 마지막 Body는 100단어 이상이면 허용)

## Quiz (선택)
- Question: …
  Options: [보기1, 보기2, 보기3]
  Answer: 0

# 작성 순서 지침
1. 먼저 주어진 정보를 기반으로 내부적으로 이야기 전체 줄거리와 페이지별 핵심 사건을 계획한다. 계획 시 [발단]→[전개]→[위기]→[절정]→[결말] 흐름에 맞춰 각 단계에서 반드시 다뤄야 할 감정과 사건을 정리한다.
2. 계획은 `## Story Outline` 섹션에 페이지 번호, 단계(괄호), 핵심 사건 한 문장으로 기록한다. (예: `3. Page 3 (전개): 루루가 별빛 공주 리아에게 도움을 청한다.`)
3. `## Story` 섹션에서는 각 페이지를 하나의 장면으로 구성하고, `Title`·`Body`·`Illustration` 순서를 지킨다. `Body`는 서술과 대사가 균형을 이루는 120~160단어(마지막 페이지는 100단어 이상)로 작성한다.

# 통합 콘셉트 요구사항
- `Art Style`: 동화책 전체의 그림 스타일을 한 문장으로 명확하게 정의한다. (예: 부드러운 파스텔 색감의 미니멀한 수채화 스타일)
- `Mood and Tone`: 글, 그림, 음성 모두에 적용될 전체적인 분위기를 정의한다. (예: 따뜻하고 모험적이며, 약간의 신비로움이 가미된 분위기)
- `Character Sheets`: `[등장인물 가이드]`에 명시된 각 캐릭터에 대해 아래 내용을 상세히 작성한다.
  - `Visual Description`: 그림 AI가 모든 페이지에서 동일한 캐릭터를 그릴 수 있도록, 외형·의상·색상·주요 특징·액세서리 등을 매우 구체적으로 묘사한다.
  - `Voice Profile`: 음성 AI가 감정을 표현할 수 있도록 목소리 톤, 빠르기, 감정을 묘사한다.

- 이야기 구조를 엄격히 [발단]→[전개]→[위기]→[절정]→[결말] 흐름으로 구성한다. 각 단계는 최소 2페이지 이상 확보하고, 분량이 넉넉하면 3페이지 이상으로 확장해 사건과 감정 변화를 풍성하게 보여준다.
- 주인공은 위기를 스스로 혹은 친구들의 도움으로 극복하며, 작은 용기가 어떻게 커다란 변화를 만드는지 보여준다.
- 전체 분량은 최소 {min_pages}페이지 이상이며, 총 단어 수는 약 {min_pages * 120}~{min_pages * 200} 단어가 되도록 한다.
- 의성어·의태어와 짧은 대화를 적절히 배치해 생동감을 준다.
- 모든 페이지를 작성한 뒤 각 페이지의 단어 수를 스스로 계산하고, 120단어(마지막 페이지는 100단어) 미만인 페이지가 하나라도 있으면 결과를 폐기한 뒤 전 페이지를 다시 작성한다.
- 마지막은 '교훈:' 같은 라벨 대신, "아무리 작아도 마음의 빛은 세상을 밝힌다"는 메시지를 따뜻하게 전달한다.

# 형식/분량 권장사항
- Story Outline은 페이지 번호 1부터 {min_pages}까지 빠짐없이 포함하고 실제 페이지 수와 일치해야 한다.
- Story 섹션의 각 `Body`는 단락 사이에 빈 줄을 넣어 가독성을 높인다.
- Quiz는 선택 사항이지만 포함할 경우 1~3문항, 보기 3개, 정답 인덱스 0~2 형태를 유지한다.
- 반드시 위 Markdown/Plaintext 포맷으로 출력한다. (JSON으로 출력하지 말 것.)

만약 위 포맷을 따를 수 없는 상황이라면, 마지막 수단으로 기존 JSON 스키마 형태로 출력하되 최소 분량 조건을 철저히 준수한다.
""".strip()

        if retry_reason:
            user_prompt += (
                f"\n\n[재시도 사유] {retry_reason}\n"
                "- 지정한 Markdown 포맷(각 섹션 헤더, Page/Title/Body/Illustration 순서)을 정확히 지킬 것.\n"
                "- Body는 페이지당 목표 단어 수를 충족하도록 다시 작성할 것.\n"
                "동일 포맷으로 완전한 결과를 다시 출력."
            )

        return [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ]

    def _normalize(self, data: dict) -> dict:
        story = data.get("story", data)

        for p in story.get("pages", []):
            if "page" not in p and "page_no" in p:
                p["page"] = p.pop("page_no")

        for q in story.get("quiz", []):
            if "q" not in q and "question" in q:
                q["q"] = q.pop("question")
            if "a" not in q and "answer" in q:
                q["a"] = q.pop("answer")

        if "quiz" not in story or story["quiz"] is None:
            story["quiz"] = []

        if "creative_concept" not in data or data["creative_concept"] is None:
            data["creative_concept"] = {
                "art_style": self._base_image_style,
                "mood_and_tone": "default",
                "character_sheets": []
            }

        outline = data.get("story_outline") or []
        normalized_outline = []
        for item in outline:
            if not isinstance(item, dict):
                continue
            entry = dict(item)
            if "page" not in entry and "page_no" in entry:
                entry["page"] = entry.pop("page_no")
            summary_val = entry.get("summary")
            if summary_val is not None:
                entry["summary"] = str(summary_val).strip()
            if "page" in entry and entry.get("summary"):
                try:
                    entry["page"] = int(entry["page"])
                except Exception:
                    continue
                phase = entry.get("phase") or entry.get("stage")
                if not phase and "(" in entry["summary"] and ")" in entry["summary"]:
                    inner = entry["summary"].rsplit("(", 1)[-1].rstrip(")")
                    phase = inner.strip()
                canonical_phase = self._normalize_stage_label(phase) if phase else None
                if not canonical_phase:
                    canonical_phase = self._extract_stage(entry.get("summary", ""))
                if canonical_phase:
                    entry["phase"] = canonical_phase
                normalized_outline.append(entry)

        if not normalized_outline:
            fallback_outline = []
            pages = story.get("pages", []) if isinstance(story, dict) else []
            for idx, page in enumerate(pages, start=1):
                text_content = ""
                heading = None
                phase_hint = None
                if isinstance(page, dict):
                    text_content = str(page.get("text", ""))
                    phase_hint = page.get("phase") or page.get("stage")
                lines = [line.strip() for line in text_content.splitlines() if line.strip()]
                if lines and lines[0].startswith("장면 제목:"):
                    heading = lines[0].split(":", 1)[-1].strip() or None
                summary = self._outline_summary(text_content)
                if not summary:
                    summary = "(요약 준비 중)"
                entry = {"page": idx, "summary": summary}
                if heading:
                    entry["summary"] = f"{heading}: {summary}"
                if phase_hint:
                    canonical = self._normalize_stage_label(phase_hint)
                    if canonical:
                        entry["phase"] = canonical
                fallback_outline.append(entry)
            normalized_outline = fallback_outline

        data["story_outline"] = normalized_outline

        return data

    def generate_text(self, req: GenerateRequest, request_id: str) -> GenerateResponse:
        moderation = Moderation()
        min_pages = self._required_min_pages(req)

        max_attempts = 4
        retry_reason: Optional[str] = None
        last_error: Optional[Exception] = None
        failure_messages: List[str] = []

        for attempt in range(1, max_attempts + 1):
            messages = self.build_prompt(req, retry_reason)

            try:
                resp = self.client.chat.completions.create(
                    model="gpt-4o",
                    messages=messages,
                    temperature=0.55,
                    max_tokens=6144,
                    user=request_id or "anon",
                )
            except Exception as exc:  # pragma: no cover - SDK failure surface
                last_error = exc
                logger.exception("Chat completion failed on attempt %s: %s", attempt, exc)
                retry_reason = "OpenAI API 호출 오류가 발생했습니다. 같은 스키마로 다시 출력하세요."
                continue

            raw = resp.choices[0].message.content.strip()
            logger.info("LLM raw output: %s", raw)

            if not raw:
                last_error = ValueError("Empty response")
                logger.warning(
                    "LLM 빈 응답 수신 (attempt=%s)", attempt
                )
                retry_reason = (
                    "내용이 비어 있습니다. 지정된 Markdown 포맷으로 전체 Creative Concept, Story Outline, Story, Quiz 섹션을 채워주세요."
                )
                continue

            raw_snippet = raw[:500].replace("\n", "\\n")
            parsed_plaintext = False
            try:
                story_data = self._parse_plaintext_story(raw)
                parsed_plaintext = True
                logger.debug("Plaintext story parsing succeeded (attempt=%s)", attempt)
            except ValueError as exc_plain:
                logger.warning(
                    "Plaintext parsing failed (attempt=%s): %s | snippet=%s",
                    attempt,
                    exc_plain,
                    raw_snippet,
                )
                try:
                    story_data = json.loads(raw)
                except json.JSONDecodeError as exc:
                    last_error = exc
                    logger.warning(
                        "LLM 응답 JSON 파싱 실패 (attempt=%s): %s | snippet=%s",
                        attempt,
                        exc,
                        raw_snippet,
                    )
                    retry_reason = (
                        "출력 포맷을 지키지 못했습니다. 지정된 Markdown 포맷 또는 JSON 스키마를 정확히 따르세요."
                    )
                    continue
                else:
                    logger.debug("JSON parsing fallback succeeded (attempt=%s)", attempt)

            story_data = self._normalize(story_data)

            try:
                story = StoryOutput(**story_data["story"])
                concept = CreativeConcept(**story_data["creative_concept"])
            except Exception as exc:
                last_error = exc
                logger.warning(
                    "LLM 응답 검증 실패 (attempt=%s): %s", attempt, exc
                )
                retry_reason = "필드 형식이 잘못되었습니다. 스키마를 준수하여 다시 생성하세요."
                continue

            page_count = len(story.pages)
            min_required_pages = max(4, min_pages)
            page_warning = page_count < min_pages
            page_block = page_count < min_required_pages

            outline_data = story_data.get("story_outline") or []
            outline_count = len(outline_data)
            outline_block = outline_count == 0
            outline_warning = False

            if outline_data and outline_count != page_count:
                outline_warning = True
                logger.warning(
                    "Story outline count mismatch: outline=%s, pages=%s",
                    outline_count,
                    page_count,
                )

            if outline_data and outline_count < min_pages:
                outline_warning = True
                logger.warning(
                    "Story outline has %s items; recommended >=%s",
                    outline_count,
                    min_pages,
                )

            stage_counts = {phase: 0 for phase in STORY_PHASES}
            stage_data_missing = False

            if outline_data:
                for idx, item in enumerate(outline_data, start=1):
                    page_ref = item.get("page") if isinstance(item, dict) else None
                    summary = item.get("summary") if isinstance(item, dict) else None
                    if page_ref is None or summary is None:
                        outline_warning = True
                        logger.warning(
                            "Story outline item %s missing page/summary", idx
                        )
                        continue
                    try:
                        page_ref = int(page_ref)
                    except Exception:
                        outline_warning = True
                        logger.warning(
                            "Story outline page value invalid: %s", page_ref
                        )
                        continue
                    if page_ref != idx:
                        outline_warning = True
                        logger.warning(
                            "Story outline page order mismatch at index %s: got %s",
                            idx,
                            page_ref,
                        )
                    if not summary.strip():
                        outline_warning = True
                        logger.warning(
                            "Story outline summary empty at page %s",
                            page_ref,
                        )
                        continue
                    stage = item.get("phase") or item.get("stage")
                    stage = self._normalize_stage_label(stage) or self._extract_stage(summary)
                    if stage and stage in stage_counts:
                        stage_counts[stage] += 1
                    else:
                        stage_data_missing = True
                        logger.warning("Story outline page %s missing valid phase label.", idx)
            else:
                outline_block = True
                logger.warning("Story outline is missing")
                stage_data_missing = True

            required_per_stage = max(1, min(3, min_pages // len(STORY_PHASES)))
            stage_block = False
            for phase, count in stage_counts.items():
                if count < required_per_stage:
                    stage_block = True
                    logger.warning(
                        "Story phase '%s' appears %s time(s); require >=%s pages for balanced arc.",
                        phase,
                        count,
                        required_per_stage,
                    )

            total_words = 0
            word_block = False
            word_warning = False
            structure_block = False
            short_pages: List[str] = []

            for idx, page in enumerate(story.pages, start=1):
                text_content = page.text or ""
                word_count = self._count_words(text_content)
                total_words += word_count

                is_last_page = idx == page_count
                required_words = MIN_LAST_PAGE_WORDS if is_last_page else MIN_PAGE_WORDS
                if word_count < required_words:
                    word_block = True
                    short_pages.append(f"{idx}페이지({word_count}단어)")
                    logger.warning(
                        "Page %s word count %s < required %s.",
                        idx,
                        word_count,
                        required_words,
                    )
                elif word_count < required_words + 30:
                    word_warning = True
                    logger.info(
                        "Page %s meets minimum word count with little buffer (%s words).",
                        idx,
                        word_count,
                    )

                lines = [line.strip() for line in text_content.splitlines() if line.strip()]
                has_heading = bool(lines) and (
                    lines[0].startswith("장면 제목:")
                    or lines[0].lower().startswith("scene title:")
                    or lines[0].lower().startswith("chapter title:")
                )
                has_illustration = any(
                    line.startswith("일러스트 묘사:")
                    or line.lower().startswith("illustration description:")
                    or line.lower().startswith("illustration cue:")
                    or line.lower().startswith("illustration:")
                    or line.lower().startswith("art prompt:")
                    for line in lines
                )

                if not has_heading:
                    structure_block = True
                    logger.warning("Page %s is missing '장면 제목:' 헤더.", idx)
                if not has_illustration:
                    structure_block = True
                    logger.warning("Page %s is missing '일러스트 묘사:' 섹션.", idx)

            min_total_words = (
                (page_count - 1) * MIN_PAGE_WORDS + (MIN_LAST_PAGE_WORDS if page_count else 0)
            )
            max_total_words = max(page_count * 220, min_pages * 200)
            total_word_block = False
            total_word_warning = False
            if total_words < min_total_words:
                total_word_block = True
                logger.warning(
                    "Story total word count %s is below minimum target %s.",
                    total_words,
                    min_total_words,
                )
            elif total_words > max_total_words:
                total_word_warning = True
                logger.info(
                    "Story total word count %s exceeds recommended ceiling %s.",
                    total_words,
                    max_total_words,
                )

            blocking_reasons: List[str] = []
            warning_reasons: List[str] = []

            if page_block:
                message = (
                    f"pages 배열이 {page_count}개만 생성되었습니다. 최소 {min_required_pages}페이지 이상은 필요합니다."
                )
                blocking_reasons.append(message)
                last_error = ValueError(message)
            elif page_warning:
                warning_reasons.append(
                    f"pages 배열이 {page_count}개입니다. 권장 분량 {min_pages}페이지에 미치지 못합니다."
                )

            if outline_block:
                message = "story_outline 배열을 생성하지 못했습니다. 모든 페이지에 대한 요약을 포함하도록 다시 생성하세요."
                blocking_reasons.append(message)
                last_error = ValueError(message)
            elif outline_warning:
                warning_reasons.append("story_outline 길이나 페이지 매칭에 경고가 있습니다. (로그 참조)")

            if stage_block or stage_data_missing:
                message = "story_outline에 발단/전개/위기/절정/결말 단계가 충분히 분배되지 않았습니다."
                blocking_reasons.append(message)
                last_error = ValueError(message)

            if word_block or total_word_block:
                details = ""
                if short_pages:
                    details = " (부족 페이지: " + ", ".join(short_pages) + ")"
                message = "페이지 또는 전체 단어 수가 최소 요구치를 충족하지 못했습니다." + details
                blocking_reasons.append(message)
                last_error = ValueError(message)
            elif word_warning or total_word_warning:
                warning_reasons.append("단어 수가 권장 범위와 차이가 있습니다. (로그 참조)")

            if structure_block:
                message = "각 페이지에 '장면 제목:'과 '일러스트 묘사:' 형식을 포함하세요."
                blocking_reasons.append(message)
                last_error = ValueError(message)

            if blocking_reasons:
                retry_reason = " ".join(blocking_reasons)
                failure_messages.append(f"attempt={attempt}: {retry_reason}")
                continue

            if warning_reasons:
                logger.info(
                    "Non-blocking story quality warnings: %s",
                    " | ".join(warning_reasons),
                )

            return GenerateResponse(
                story=story,
                creative_concept=concept,
                raw_json=json.dumps(story_data, ensure_ascii=False),
                moderation=moderation,
            )

        summary_reason = retry_reason or "LLM output did not meet minimum requirements"
        if failure_messages:
            summary_reason += " | " + " | ".join(failure_messages)
        raise ValueError(summary_reason) from last_error

    # --------------------------------------------------------------------------------------
    # Image generation (MODIFIED)
    # --------------------------------------------------------------------------------------
    def generate_image(
        self,
        text: str,
        request_id: str,
        art_style: Optional[str] = None,
        character_visuals: Optional[List[CharacterVisual]] = None,
        character_images: Optional[List[CharacterProfile]] = None, # Kept for fallback
    ) -> str:
        
        # Prioritize the new art_style from creative_concept
        style_guide = art_style or self._base_image_style

        prompt = dedent(
            f"""
            {style_guide}
            Scene description: {text}
            """
        ).strip()

        # Prioritize detailed visual descriptions from the creative_concept
        if character_visuals:
            descriptions = []
            for visual in character_visuals:
                descriptions.append(f"- {visual.name}: {visual.visual_description}")
            if descriptions:
                prompt += "\n\nCharacters to include (follow these descriptions strictly):\n" + "\n".join(descriptions)
        
        # Fallback to old character prompt keywords if new visuals are not provided
        elif character_images:
            descriptions = []
            for profile in character_images:
                parts = [profile.name]
                if profile.persona:
                    parts.append(profile.persona)
                if profile.prompt_keywords:
                    parts.append(f"Visual cues: {profile.prompt_keywords}")
                descriptions.append(" | ".join(filter(None, parts)))
            if descriptions:
                prompt += "\n\nCharacters to include:\n" + "\n".join(f"- {desc}" for desc in descriptions)

        provider_candidates: List[ImageProvider] = []
        primary_provider = self._get_image_provider()
        provider_candidates.append(primary_provider)

        image_bytes = None
        last_error: Optional[Exception] = None

        for provider in provider_candidates:
            provider_name = (
                "Gemini"
                if provider is self._gemini_image_provider
                else "OpenAI"
            )
            logger.info(
                "Generating image via %s for request_id %s", provider_name, request_id
            )
            try:
                image_bytes = provider.generate(
                    prompt=prompt,
                    request_id=request_id,
                )
                break
            except ImageProviderError as exc:
                last_error = exc
                logger.warning(
                    "%s image provider failed for %s: %s",
                    provider_name,
                    request_id,
                    exc,
                )

        if image_bytes is None:
            raise last_error if last_error else ImageProviderError(
                "Image generation failed for all providers"
            )

        image = Image.open(BytesIO(image_bytes)).convert("RGB")
        image = image.resize((512, 512), Image.LANCZOS)
        buffer = BytesIO()
        image.save(buffer, format="PNG")
        encoded = base64.b64encode(buffer.getvalue()).decode("utf-8")

        return encoded

    def _get_image_provider(self) -> ImageProvider:
        if self._prefer_gemini and self._gemini_image_provider is not None:
            return self._gemini_image_provider
        return self._openai_image_provider

    @staticmethod
    def _parse_image_dimensions(value: str) -> Optional[Tuple[int, int]]:
        try:
            width_str, height_str = value.lower().split("x", 1)
            width = int(width_str.strip())
            height = int(height_str.strip())
            if width > 0 and height > 0:
                return width, height
        except Exception:
            logger.warning("Invalid image size format for Gemini image_dimensions: %s", value)
        return None

    # ... (rest of the file remains the same) ...
    # --------------------------------------------------------------------------------------
    # Audio generation
    # --------------------------------------------------------------------------------------
    def create_tts(self, text: str, voice: str = "alloy") -> bytes:
        cleaned = self._clean_text_for_tts(text)
        snippet = cleaned[:40].replace("\n", " ")
        logger.info("Generating TTS chunk (%s): %s...", voice, snippet)
        response = self.client.audio.speech.create(
            model="tts-1",
            voice=voice,
            input=cleaned,
            response_format="wav",
        )
        return response.read()

    def plan_reading_segments(
        self,
        audio_request: GenerateAudioRequest,
        request_id: str,
    ) -> List[dict]:
        character_lines = []
        for character in audio_request.characters:
            persona = getattr(character, "persona", "") or ""
            catchphrase = getattr(character, "catchphrase", "") or ""
            keywords = getattr(character, "prompt_keywords", None) or getattr(character, "promptKeywords", None) or ""
            character_lines.append(
                {
                    "slug": character.slug or character.name,
                    "name": character.name,
                    "persona": persona,
                    "catchphrase": catchphrase,
                    "keywords": keywords,
                }
            )

        pages_text = "\n".join(
            f"Page {page.page_no}: {page.text}" for page in audio_request.pages
        )
        character_prompt = "\n".join(
            f"- slug: {c['slug']} | name: {c['name']} | persona: {c['persona']} | catchphrase: {c['catchphrase']} | keywords: {c['keywords']}"
            for c in character_lines
        ) or "- (none)"

        system_prompt = "너는 오디오북 연출가다. 주어진 동화를 자연스럽게 낭독하기 위한 세그먼트 계획을 JSON 포맷으로 작성해라."
        user_prompt = dedent(
            f"""
### Story Meta
Title: {audio_request.title or '제목 미정'}
Language: {audio_request.language or 'KO'}

### Characters
{character_prompt}

### Story Pages
{pages_text}

### Requirements
- JSON 객체 형태로만 응답할 것. 키는 `segments` 하나만 둔다.
- segments는 배열이며, 각 요소는 아래 필드를 가진다:
  * segment_type: "narration" 또는 "dialogue"
  * speaker: 내레이션이면 "narrator", 대사면 캐릭터 slug를 사용
  * emotion: 감정 표현이나 말투 (예: "따뜻하고 차분하게")
  * text: 실제 읽을 문장. 대사는 따옴표 없이 발화를 남기고, 서술은 원문을 그대로 유지하되 불필요한 공백을 제거
- 같은 화자/감정이 연속되면 세그먼트를 하나로 묶어도 된다.
- 문장 안에 따옴표("")로 감싼 대사와 서술이 섞여 있으면, 서술 부분은 `narration`, 따옴표 안 문장은 `dialogue`로 순서를 유지하며 각각 별도 세그먼트로 나눌 것.
- "대사" 루루가 말했어요`처럼 대사 앞·뒤에 붙은 서술은 각각 narration 세그먼트로 추가하고, 절대로 생략하거나 대사와 합치지 말 것.
- 대사 뒤에 붙는 짧은 서술(예: "…!" 미미가 외쳤어요.)도 반드시 narration 세그먼트로 남기고, 어떤 서술도 생략하지 말 것.
- 원본 텍스트의 모든 문장과 단어를 빠짐없이 포함해야 한다. 어떤 이유로도 생략하거나 요약하지 말 것.
- narration 세그먼트의 `text`에는 해당 문장을 원문 그대로(띄어쓰기만 정리) 담고, `dialogue` 세그먼트의 `text`에는 따옴표를 제거한 발화만 담을 것. `text` 값에 따옴표가 필요한 경우 반드시 `\"`처럼 escape해서 JSON이 깨지지 않도록 한다.
- 화자는 문맥상 가장 최근에 이름이 언급된 캐릭터 slug를 사용하고, 특정할 수 없으면 narrator로 둔다.
- 의성어와 배경 설명이 자연스럽게 이어지도록 구성하라.
- JSON 이외의 설명은 포함하지 말 것.
"""
        ).strip()

        response = self.client.chat.completions.create(
            model=self.reading_plan_model,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            temperature=0.4,
            max_tokens=2048,
            user=request_id or "anon",
            response_format={"type": "json_object"},
        )

        raw = response.choices[0].message.content.strip()
        logger.info("Reading plan raw output: %s", raw)
        data = json.loads(raw)
        segments = data.get("segments")
        if not isinstance(segments, list):
            raise ValueError("Reading plan response missing segments array.")
        return segments

    def synthesize_story_audio(
        self,
        audio_request: GenerateAudioRequest,
        request_id: str,
    ) -> bytes:
        segments = self.plan_reading_segments(audio_request, request_id)
        if not segments:
            raise ValueError("Reading plan returned no segments.")

        if self.azure_client is not None:
            try:
                ssml = self._build_azure_ssml(audio_request, segments)
                return self.azure_client.synthesize_ssml(ssml)
            except Exception:
                logger.exception("Azure TTS synthesis failed; falling back to OpenAI per-segment TTS")

        char_by_slug = {
            getattr(character, "slug", None) or character.name: character
            for character in audio_request.characters
        }
        char_by_name = {
            character.name.lower(): getattr(character, "slug", None) or character.name
            for character in audio_request.characters
        }

        audio_chunks: List[bytes] = []
        for segment in segments:
            raw_text = (segment.get("text") or "").strip()
            if not raw_text:
                continue

            cleaned_text = self._clean_text_for_tts(raw_text)
            if not cleaned_text:
                continue

            segment_type = (segment.get("segment_type") or "narration").lower()
            speaker = (segment.get("speaker") or "narrator").strip()

            slug = speaker
            if segment_type != "narration" and slug not in char_by_slug:
                lookup = char_by_name.get(speaker.lower())
                if lookup:
                    slug = lookup

            preset = self._resolve_voice(segment_type, slug)
            voice = preset.get("voice", "alloy")
            if voice not in SUPPORTED_OPENAI_VOICES:
                voice = VOICE_PRESETS["default"]["voice"]

            audio_bytes = self.create_tts(cleaned_text, voice=voice)
            audio_chunks.append(audio_bytes)

        if not audio_chunks:
            raise ValueError("No audio data generated from segments.")

        return self._merge_wav_segments(audio_chunks)

    # --------------------------------------------------------------------------------------
    # Helpers
    # --------------------------------------------------------------------------------------
    def _resolve_voice(self, segment_type: str, speaker_slug: str) -> Dict[str, str]:
        if segment_type == "narration":
            return VOICE_PRESETS["narration"]

        preset = VOICE_PRESETS["characters"].get(speaker_slug)
        if preset is None:
            return VOICE_PRESETS["default"]
        return preset

    def _clean_text_for_tts(self, text: str) -> str:
        return text.replace('"', '').replace('“', '').replace('”', '').strip()

    def _merge_wav_segments(self, wav_segments: List[bytes]) -> bytes:
        if not wav_segments:
            raise ValueError("No audio segments provided for merge.")
        params = None
        frames = []
        for segment in wav_segments:
            with wave.open(BytesIO(segment), "rb") as wf:
                frame_params = wf.getparams()
                if params is None:
                    params = frame_params
                else:
                    if (
                        frame_params.nchannels != params.nchannels
                        or frame_params.sampwidth != params.sampwidth
                        or frame_params.framerate != params.framerate
                    ):
                        raise ValueError("Inconsistent audio parameters between segments.")
                frames.append(wf.readframes(wf.getnframes()))
        total_size = sum(len(f) for f in frames)
        max_wav_size = (1 << 32) - 1
        output = BytesIO()
        if total_size >= max_wav_size:
            logger.warning("Merged audio would exceed WAV size limit; falling back to raw concatenation.")
            output.write(b"".join(frames))
            return output.getvalue()

        sampwidth = params.sampwidth
        nchannels = params.nchannels
        framerate = params.framerate
        frame_size = sampwidth * nchannels
        with wave.open(output, "wb") as wf:
            wf.setnchannels(nchannels)
            wf.setsampwidth(sampwidth)
            wf.setframerate(framerate)
            total_frames = sum(len(frame) // frame_size for frame in frames) if frame_size else 0
            wf.setnframes(total_frames)
            for frame in frames:
                wf.writeframes(frame)
        return output.getvalue()

    def _determine_locale(self, language: Optional[str]) -> str:
        if not language:
            return "ko-KR"
        normalized = str(language).strip().lower()
        if normalized in {"ko", "ko-kr", "korean"}:
            return "ko-KR"
        if normalized in {"en", "en-us", "english"}:
            return "en-US"
        return "ko-KR"

    def _map_emotion_to_style(self, emotion: str) -> Optional[str]:
        lowered = emotion.lower()
        if any(keyword in lowered for keyword in ["기쁘", "신나", "즐겁", "흥분"]):
            return "cheerful"
        if any(keyword in lowered for keyword in ["차분", "잔잔", "평온", "따뜻"]):
            return "calm"
        if any(keyword in lowered for keyword in ["속삭", "조용", "은은"]):
            return "whispering"
        if any(keyword in lowered for keyword in ["용감", "힘차", "모험"]):
            return "energetic"
        if any(keyword in lowered for keyword in ["상냥", "부드럽", "다정"]):
            return "friendly"
        if any(keyword in lowered for keyword in ["신비", "꿈", "별"]):
            return "hopeful"
        return None

    def _build_azure_ssml(self, audio_request: GenerateAudioRequest, segments: List[dict]) -> str:
        locale = self._determine_locale(audio_request.language)
        parts = [
            '<?xml version="1.0" encoding="utf-8"?>',
            (
                f'<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" '
                f'xmlns:mstts="https://www.w3.org/2001/mstts" xml:lang="{locale}" >'
            ),
        ]

        char_by_slug = {
            getattr(character, "slug", None) or character.name: character
            for character in audio_request.characters
        }
        char_by_name = {
            character.name.lower(): getattr(character, "slug", None) or character.name
            for character in audio_request.characters
        }

        for segment in segments:
            raw_text = (segment.get("text") or "").strip()
            if not raw_text:
                continue
            slug = (segment.get("speaker") or "narrator").strip()
            segment_type = (segment.get("segment_type") or "narration").lower()
            if segment_type != "narration" and slug not in char_by_slug:
                lookup = char_by_name.get(slug.lower())
                if lookup:
                    slug = lookup

            preset = self._resolve_voice(segment_type, slug)
            voice_name = (
                preset.get("azure_voice")
                or VOICE_PRESETS["default"].get("azure_voice")
                or "ko-KR-SunHiNeural"
            )
            style = preset.get("azure_style")
            styledegree = preset.get("azure_styledegree")
            rate = preset.get("azure_rate")

            emotion = segment.get("emotion") or ""
            inferred_style = self._map_emotion_to_style(emotion)
            if inferred_style:
                style = inferred_style

            express_open = ""
            express_close = ""
            if style:
                attrs = [f'style="{style}"']
                if styledegree:
                    attrs.append(f'styledegree="{styledegree}"')
                express_open = f"<mstts:express-as {' '.join(attrs)} >"
                express_close = "</mstts:express-as>"
            prosody_open = ""
            prosody_close = ""
            if rate:
                prosody_open = f'<prosody rate="{rate}" >'
                prosody_close = "</prosody>"

            text_content = escape(self._clean_text_for_tts(raw_text))

            parts.append(
                f'<voice name="{voice_name}">{express_open}{prosody_open}'
                f'{text_content}{prosody_close}{express_close}</voice>'
            )

        parts.append("</speak>")
        return "".join(parts)
