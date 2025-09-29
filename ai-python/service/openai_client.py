# service/openai_client.py
from html import escape
from typing import Dict, List, Optional
import random

from openai import BadRequestError, OpenAI

from config import Config
from schemas import (
    CharacterProfile,
    CharacterVisual, # IMPORT NEW
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

logger = logging.getLogger(__name__)

# ... (VOICE_PRESETS and other constants remain the same) ...

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

    # ... (build_prompt, _normalize, generate_text methods remain the same) ...
    def build_prompt(self, req: GenerateRequest, retry_reason: Optional[str] = None) -> list:
        is_ko = str(req.language).upper() == "KO"
        topics_str = ", ".join(req.topics)
        objectives_str = ", ".join(req.objectives)
        lang_label = "한국어" if is_ko else "영어"
        title_line = f'[제목] "{req.title}" (고정)' if req.title else "[제목] 미정(직접 생성)"

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
            "- 출력은 반드시 JSON 하나만. 추가 텍스트/설명/코드블록 금지."
        )

        user_prompt = f'''
[연령대] {req.age_range}세
[주제] {topics_str}
[학습목표] {objectives_str}
[언어] {lang_label}
{title_line}
{character_prompt_section}

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
  "story": {{
    "title": "string",
    "pages": [{{"page": 1, "text": "string"}}],
    "quiz": [{{"q": "string", "options": ["string","string","string"], "a": 0}}]
  }}
}}

# 형식 예시(참고용, 실제 내용은 달라야 함)
{{
  "creative_concept": {{
    "art_style": "따뜻한 색감의 동화적 디지털 드로잉 스타일",
    "mood_and_tone": "용기와 우정에 대한 따뜻하고 교훈적인 분위기",
    "character_sheets": [
      {{
        "name": "토토",
        "visual_description": "크고 동그란 갈색 눈을 가진 아기 토끼. 항상 작은 노란색 가방을 메고 다님.",
        "voice_profile": "호기심 많고 약간 수줍은 소년의 목소리"
      }}
    ]
  }},
  "story": {{
    "title": "용감한 토끼 토토",
    "pages": [
      {{"page": 1, "text": "깊은 숲속에 사는 아기 토끼 토토는 겁이 아주 많았어요. 작은 바스락 소리에도 깜짝 놀라 노란 가방 뒤에 숨기 바빴죠. 친구들은 그런 토토를 놀리곤 했지만, 토토는 용감해지고 싶었어요."}},
      {{"page": 2, "text": "어느 날, 숲의 가장 큰 나무 꼭대기에 반짝이는 별사탕이 열렸다는 소문이 퍼졌어요. 하지만 그 나무는 아주 높고 무서운 절벽 위에 있었죠. 아무도 그곳에 갈 엄두를 내지 못했어요."}},
      {{"page": 3, "text": "토토는 결심했어요. \"내가 저 별사탕을 가져와서 모두에게 용기를 보여줄 거야!\" 토토는 작은 발걸음을 옮기기 시작했어요. 숲속 친구들은 모두 토토를 걱정스럽게 바라보았죠."}}
    ],
    "quiz": [
      {{"q": "토토의 가장 큰 소원은 무엇이었나요?", "options": ["키가 커지는 것", "용감해지는 것", "부자가 되는 것"], "a": 1}}
    ]
  }}
}}

# 통합 콘셉트 요구사항
- `art_style`: 동화책 전체의 그림 스타일을 한 문장으로 명확하게 정의할 것. (예: 부드러운 파스텔 색감의 미니멀한 수채화 스타일)
- `mood_and_tone`: 글, 그림, 음성 모두에 적용될 전체적인 분위기를 정의할 것. (예: 따뜻하고 모험적이며, 약간의 신비로움이 가미된 분위기)
- `character_sheets`: `[등장인물 가이드]`에 명시된 각 캐릭터에 대해 아래 내용을 상세히 작성할 것.
  - `visual_description`: 그림 AI가 모든 페이지에서 동일한 캐릭터를 그릴 수 있도록, 외형, 의상, 색상, 주요 특징, 액세서리 등을 매우 구체적으로 묘사할 것.
  - `voice_profile`: 음성 AI가 캐릭터의 감정을 표현할 수 있도록, 목소리 톤, 빠르기, 감정(예: 높고 밝은 톤, 호기심 가득한 어조)을 묘사할 것.

# 스토리텔링 요구사항
- 이야기 구조를 반드시 반영: [발단]→[전개]→[위기]→[절정]→[결말] 순서를 내부적으로 준수하되, 텍스트에는 라벨을 절대 표기하지 말 것.
- 주인공이 해결해야 할 명확한 위기나 도전 과제를 반드시 포함할 것. 주인공은 최소 한 번의 어려움을 겪고, 자신의 힘이나 친구의 도움으로 극복해야 함.
- 분량은 10~15페이지를 권장한다. 각 페이지는 3~4개의 문장으로 구성된 하나의 문단이어야 한다.
- 의성어·의태어와 짧은 대화를 적절히 배치해 생동감을 줄 것.
- 마지막은 '교훈:' 같은 라벨 대신, 따뜻한 정서 문장으로 자연스럽게 교훈을 전달한다.

# 형식/분량 규칙
- pages는 최소 {req.min_pages}개 이상, 권장 10~15개. page는 1부터 1씩 증가.
- quiz는 0~3개, options는 3개, a는 0부터 시작하는 정답 인덱스.
- 키/구조를 절대 바꾸지 말 것. JSON 외 다른 텍스트 금지.
'''.strip()

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

        return data

    def generate_text(self, req: GenerateRequest, request_id: str) -> GenerateResponse:
        moderation = Moderation()
        messages = self.build_prompt(req)

        resp = self.client.chat.completions.create(
            model="gpt-4o-mini",
            messages=messages,
            temperature=0.7,
            max_tokens=2048,
            user=request_id or "anon",
            response_format={"type": "json_object"},
        )

        raw = resp.choices[0].message.content.strip()
        logger.info("LLM raw output: %s", raw)

        story_data = json.loads(raw)
        story_data = self._normalize(story_data)

        story = StoryOutput(**story_data["story"])
        concept = CreativeConcept(**story_data["creative_concept"])

        return GenerateResponse(
            story=story,
            creative_concept=concept,
            raw_json=json.dumps(story_data, ensure_ascii=False),
            moderation=moderation,
        )

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
            f'''
            {style_guide}
            Scene description: {text}
            '''
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

        logger.info("Generating image for request_id %s with prompt: %s", request_id, prompt)

        try:
            response = self.client.images.generate(
                model=self.image_model,
                prompt=prompt,
                size=self.image_size,
                quality=self.image_quality,
                n=1,
            )
        except BadRequestError as exc:
            logger.error("Image generation failed for %s: %s", request_id, exc)
            raise

        raw_data = response.data[0]
        image_base64 = getattr(raw_data, "b64_json", None)
        if image_base64 is None:
            image_url = getattr(raw_data, "url", None)
            if not image_url:
                raise ValueError("Image generation response missing both b64_json and url fields")
            import requests

            download = requests.get(image_url, timeout=30)
            download.raise_for_status()
            image_bytes = download.content
        else:
            image_bytes = base64.b64decode(image_base64)

        image = Image.open(BytesIO(image_bytes)).convert("RGB")
        image = image.resize((512, 512), Image.LANCZOS)
        buffer = BytesIO()
        image.save(buffer, format="PNG")
        encoded = base64.b64encode(buffer.getvalue()).decode("utf-8")

        return encoded

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
        self, audio_request: GenerateAudioRequest, request_id: str
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
            f'''
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
- `"대사" 루루가 말했어요`처럼 대사 앞·뒤에 붙은 서술은 각각 narration 세그먼트로 추가하고, 절대로 생략하거나 대사와 합치지 말 것.
- 대사 뒤에 붙는 짧은 서술(예: "…!" 미미가 외쳤어요.)도 반드시 narration 세그먼트로 남기고, 어떤 서술도 생략하지 말 것.
- 원본 텍스트의 모든 문장과 단어를 빠짐없이 포함해야 한다. 어떤 이유로도 생략하거나 요약하지 말 것.
- narration 세그먼트의 `text`에는 해당 문장을 원문 그대로(띄어쓰기만 정리) 담고, `dialogue` 세그먼트의 `text`에는 따옴표를 제거한 발화만 담을 것. `text` 값에 따옴표가 필요한 경우 반드시 `\"`처럼 escape해서 JSON이 깨지지 않도록 한다.
- 화자는 문맥상 가장 최근에 이름이 언급된 캐릭터 slug를 사용하고, 특정할 수 없으면 narrator로 둔다.
- 의성어와 배경 설명이 자연스럽게 이어지도록 구성하라.
- JSON 이외의 설명은 포함하지 말 것.
'''
        )

        response = self.client.chat.completions.create(
            model=self.reading_plan_model,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            temperature=0.4,
            max_tokens=900,
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
        self, audio_request: GenerateAudioRequest, request_id: str
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
                voice = VOICE_PRESETS["default"].get("voice", "alloy")

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
                f'xmlns:mstts="https://www.w3.org/2001/mstts" xml:lang="{locale}" >',
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
