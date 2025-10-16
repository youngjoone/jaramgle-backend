# -*- coding: utf-8 -*-
import logging
import wave
from html import escape
from io import BytesIO
from typing import Dict, List, Optional, Tuple
import json
from textwrap import dedent

import google.generativeai as genai
from openai import OpenAI

from config import Config
from schemas import CharacterProfile
from service.azure_tts_client import AzureTTSClient, AzureTTSConfigurationError

logger = logging.getLogger(__name__) 

# --- Service-level client initialization ---

_openai_client = OpenAI(api_key=Config.OPENAI_API_KEY)

_azure_client: Optional[AzureTTSClient] = None
logger.info("USE_AZURE_TTS=%s, region=%s", Config.USE_AZURE_TTS, Config.AZURE_SPEECH_REGION or "(unset)")
if Config.USE_AZURE_TTS:
    try:
        _azure_client = AzureTTSClient(
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
    except Exception:
        logger.exception("Failed to initialise Azure TTS client; falling back to OpenAI")

# --- Voice & Style Definitions (Copied from openai_client) ---

SUPPORTED_OPENAI_VOICES = {"alloy", "ash", "coral", "fable", "onyx", "sage", "echo", "nova", "shimmer"}

VOICE_PRESETS: Dict[str, dict] = {
    "narration": {
        "voice": "alloy",
        "style": "Warm and calm mother's voice",
        "azure_voice": "ko-KR-SunHiNeural",
        "azure_style": "friendly",
        "azure_styledegree": "1.0",
        "azure_rate": "0%",
    },
    "default": {
        "voice": "alloy",
        "style": "Natural and comfortable",
        "azure_voice": "ko-KR-SunHiNeural",
        "azure_style": "calm",
        "azure_styledegree": "1.0",
        "azure_rate": "0%",
    },
    "characters": {
        "lulu-rabbit": {
            "voice": "coral",
            "style": "Lively and cute",
            "azure_voice": "ko-KR-SunHiNeural",
            "azure_style": "cheerful",
            "azure_styledegree": "1.2",
            "azure_rate": "+10%",
        },
        "mungchi-puppy": {
            "voice": "nova",
            "style": "Friendly and joyful",
            "azure_voice": "ko-KR-SoonBokNeural",
            "azure_style": "friendly",
            "azure_styledegree": "1.1",
            "azure_rate": "+6%",
        },
        "coco-squirrel": {
            "voice": "echo",
            "style": "Fast and exciting",
            "azure_voice": "ko-KR-SeoHyeonNeural",
            "azure_style": "excited",
            "azure_styledegree": "1.2",
            "azure_rate": "+12%",
        },
        "ria-princess": {
            "voice": "shimmer",
            "style": "Elegant and sweet",
            "azure_voice": "ko-KR-SeoHyeonNeural",
            "azure_style": "gentle",
            "azure_styledegree": "1.1",
            "azure_rate": "0%",
        },
        "lucas-prince": {
            "voice": "fable",
            "style": "Playful and brave",
            "azure_voice": "ko-KR-SunHiNeural",
            "azure_style": "cheerful",
            "azure_styledegree": "1.1",
            "azure_rate": "+8%",
        },
        "geo-explorer": {
            "voice": "ash",
            "style": "Calm but brave",
            "azure_voice": "ko-KR-SoonBokNeural",
            "azure_style": "calm",
            "azure_styledegree": "1.0",
            "azure_rate": "0%",
        },
        "robo-roro": {
            "voice": "onyx",
            "style": "Mechanical yet warm",
            "azure_voice": "ko-KR-SoonBokNeural",
            "azure_style": "calm",
            "azure_styledegree": "1.0",
            "azure_rate": "-4%",
        },
        "mimi-fairy": {
            "voice": "sage",
            "style": "Whispering and affectionate",
            "azure_voice": "ko-KR-SeoHyeonNeural",
            "azure_style": "whispering",
            "azure_styledegree": "1.0",
            "azure_rate": "-6%",
        },
        "pipi-math-monster": {
            "voice": "coral",
            "style": "Upbeat and lively",
            "azure_voice": "ko-KR-SunHiNeural",
            "azure_style": "cheerful",
            "azure_styledegree": "1.1",
            "azure_rate": "+10%",
        },
        "nova-space": {
            "voice": "nova",
            "style": "Dreamy and mysterious",
            "azure_voice": "ko-KR-SunHiNeural",
            "azure_style": "hopeful",
            "azure_styledegree": "1.1",
            "azure_rate": "+4%",
        },
    },
}

def _build_reading_plan_prompt(story_text: str, characters: List[CharacterProfile]) -> str:
    """오디오 읽기 계획 생성을 위한 프롬프트를 생성합니다."""
    
    character_descs = []
    for char in characters:
        name = getattr(char, "name", "Unknown")
        slug = getattr(char, "slug", None) or name
        persona = getattr(char, "persona", None)
        if not persona:
            persona = getattr(char, "visual_description", None)
        if not persona:
            persona = getattr(char, "prompt_keywords", None)
        persona = persona or "No additional description provided"
        desc = f"- {name} ({slug}): {persona}"
        character_descs.append(desc)
    characters_str = "\n".join(character_descs)

    prompt = f"""
너는 전문 오디오북 프로듀서다. 주어진 동화 텍스트를 가지고, TTS가 자연스럽게 읽을 수 있도록 오디오 연출 계획(reading plan)을 짜야 한다.

# 입력 텍스트
---
{story_text}
---

# 등장인물 정보
---
{characters_str}
- narrator: 전체 이야기를 설명하는 나레이터
---

# 출력 스키마 (JSON 형식)
- 전체 텍스트를 담는 `reading_plan`이라는 키를 가진 JSON 배열을 출력해야 한다.
- 각 배열 요소는 다음 키를 가진 객체다.
  - `segment_type`: "narration" 또는 "dialogue"
  - `speaker`: 말하는 사람. 등장인물 정보에 있는 `slug` 값 또는 "narrator"를 사용한다.
  - `emotion`: 문장의 감정이나 톤. (예: cheerful, sad, calm, excited, whispering, friendly, hopeful)
  - `text`: 실제 TTS가 읽을 문장.

# 작업 지침
1.  입력 텍스트 전체를 빠짐없이 `reading_plan`에 포함시켜야 한다.
2.  텍스트를 의미 단위로 나누어 여러 세그먼트로 만든다. 너무 짧게 나누지 않도록 주의한다.
3.  각 세그먼트가 나레이션인지, 특정 캐릭터의 대사인지 판단하여 `segment_type`과 `speaker`를 정확히 지정한다.
4.  문맥에 맞는 `emotion`을 풍부하게 지정하여 오디오북의 생동감을 더한다.
5.  **중요**: 전체 `reading_plan` 배열의 길이는 반드시 50개 미만이어야 한다. 텍스트를 너무 잘게 나누지 않도록 주의한다.
6.  최종 출력은 반드시 JSON 객체 하나여야 한다. (예: `{{"reading_plan": [...]}}`) 추가 설명이나 코드 블록은 절대 포함하지 않는다.

"""
    return dedent(prompt).strip()

def plan_reading_segments(story_text: str, characters: List[CharacterProfile], request_id: str) -> List[Dict]:
    """LLM을 호출하여 오디오 읽기 계획을 생성합니다."""
    
    # 현재는 Gemini만 지원, 필요시 OpenAI 추가
    provider = Config.LLM_PROVIDER.lower()
    if provider != 'gemini':
        raise NotImplementedError("Reading plan generation is currently only supported for Gemini.")

    client = genai.GenerativeModel(
        model_name="models/gemini-2.5-flash", # 1.5 Flash 모델 사용
        generation_config={"response_mime_type": "application/json"}
    )
    
    prompt = _build_reading_plan_prompt(story_text, characters)
    
    logger.info(f"Calling Gemini for reading plan generation. Request ID: {request_id}")
    
    try:
        response = client.generate_content(
            prompt,
            generation_config=genai.types.GenerationConfig(temperature=0.5),
        )
        raw_json_text = response.text
        logger.info(f"Gemini raw response for reading plan ({request_id}): {raw_json_text[:300]}...")
        
        data = json.loads(raw_json_text)
        return data.get("reading_plan", [])

    except Exception as e:
        logger.error(f"Failed to generate reading plan for request {request_id}: {e}", exc_info=True)
        raise

# --- Private Helper Functions ---

def _clean_text_for_tts(text: str) -> str:
    return text.replace('"', '').replace('“', '').replace('”', '').strip()

def _resolve_voice(segment_type: str, speaker_slug: str) -> Dict[str, str]:
    if segment_type == "narration":
        return VOICE_PRESETS["narration"]
    return VOICE_PRESETS["characters"].get(speaker_slug) or VOICE_PRESETS["default"]

def _merge_wav_segments(wav_segments: List[bytes]) -> bytes:
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

def _determine_locale(language: Optional[str]) -> str:
    if not language:
        return "ko-KR"
    normalized = str(language).strip().lower()
    if normalized in {"ko", "ko-kr", "korean"}:
        return "ko-KR"
    if normalized in {"en", "en-us", "english"}:
        return "en-US"
    return "ko-KR"

def _map_emotion_to_style(emotion: str) -> Optional[str]:
    lowered = (emotion or "").lower()
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

def _build_azure_ssml(reading_plan: List[dict], characters: List[CharacterProfile], language: Optional[str]) -> str:
    locale = _determine_locale(language)
    parts = [
        '<?xml version="1.0" encoding="utf-8"?>',
        (
            f'<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" '
            f'xmlns:mstts="https://www.w3.org/2001/mstts" xml:lang="{locale}" >'
        ),
    ]

    char_by_slug = {char.slug or char.name: char for char in characters}
    char_by_name = {char.name.lower(): char.slug or char.name for char in characters}

    for segment in reading_plan:
        raw_text = (segment.get("text") or "").strip()
        if not raw_text:
            continue
        slug = (segment.get("speaker") or "narrator").strip()
        segment_type = (segment.get("segment_type") or "narration").lower()
        if segment_type != "narration" and slug not in char_by_slug:
            lookup = char_by_name.get(slug.lower())
            if lookup:
                slug = lookup

        preset = _resolve_voice(segment_type, slug)
        voice_name = (
            preset.get("azure_voice")
            or VOICE_PRESETS["default"].get("azure_voice")
            or "ko-KR-SunHiNeural"
        )
        style = preset.get("azure_style")
        styledegree = preset.get("azure_styledegree")
        rate = preset.get("azure_rate")

        emotion = segment.get("emotion") or ""
        inferred_style = _map_emotion_to_style(emotion)
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

        text_content = escape(_clean_text_for_tts(raw_text))

        parts.append(
            f'<voice name="{voice_name}">{express_open}{prosody_open}'
            f'{text_content}{prosody_close}{express_close}</voice>'
        )

    parts.append("</speak>")
    return "".join(parts)

# --- Public API ---

def create_tts(text: str, voice: str = "alloy") -> bytes:
    """Generates a simple TTS audio from text using OpenAI."""
    cleaned = _clean_text_for_tts(text)
    logger.info("Generating TTS chunk (%s): %s...", voice, cleaned[:40])
    response = _openai_client.audio.speech.create(
        model="tts-1",
        voice=voice,
        input=cleaned,
        response_format="wav",
    )
    return response.read()

def synthesize_story_from_plan(
    reading_plan: List[dict],
    characters: List[CharacterProfile],
    language: Optional[str],
    request_id: str
) -> bytes:
    """
    Synthesizes a full story audio from a pre-generated reading plan.
    It does NOT call an LLM to create the plan.
    """
    if not reading_plan:
        raise ValueError("Reading plan cannot be empty.")

    if _azure_client is not None:
        try:
            ssml = _build_azure_ssml(reading_plan, characters, language)
            logger.info(f"Synthesizing audio from SSML for request {request_id}")
            return _azure_client.synthesize_ssml(ssml)
        except Exception:
            logger.exception("Azure TTS synthesis failed; falling back to OpenAI per-segment TTS")

    char_by_slug = {char.slug or char.name: char for char in characters}
    char_by_name = {char.name.lower(): char.slug or char.name for char in characters}

    audio_chunks: List[bytes] = []
    for segment in reading_plan:
        raw_text = (segment.get("text") or "").strip()
        if not raw_text:
            continue

        segment_type = (segment.get("segment_type") or "narration").lower()
        speaker = (segment.get("speaker") or "narrator").strip()

        slug = speaker
        if segment_type != "narration" and slug not in char_by_slug:
            slug = char_by_name.get(speaker.lower(), slug)

        preset = _resolve_voice(segment_type, slug)
        voice = preset.get("voice", "alloy")
        if voice not in SUPPORTED_OPENAI_VOICES:
            voice = VOICE_PRESETS["default"]["voice"]

        audio_bytes = create_tts(raw_text, voice=voice)
        audio_chunks.append(audio_bytes)

    if not audio_chunks:
        raise ValueError("No audio data generated from segments.")

    return _merge_wav_segments(audio_chunks)

def plan_and_synthesize_audio(
    story_text: str,
    characters: List[CharacterProfile],
    language: Optional[str],
    request_id: str
) -> bytes:
    """
    Generates a reading plan from story text and then synthesizes audio from that plan.
    Combines plan_reading_segments and synthesize_story_from_plan.
    """
    reading_plan = plan_reading_segments(story_text, characters, request_id)
    audio_bytes = synthesize_story_from_plan(reading_plan, characters, language, request_id)
    return audio_bytes
