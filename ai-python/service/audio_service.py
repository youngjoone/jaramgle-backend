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
from service.gemini_tts_client import GeminiTTSClient, GeminiTTSConfigurationError

logger = logging.getLogger(__name__) 

# --- Service-level client initialization ---

_openai_client = OpenAI(api_key=Config.OPENAI_API_KEY)

_azure_client: Optional[AzureTTSClient] = None
_gemini_tts_client: Optional[GeminiTTSClient] = None
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

logger.info(
    "USE_GEMINI_TTS=%s, model=%s",
    Config.USE_GEMINI_TTS,
    Config.GEMINI_TTS_MODEL or "(unset)",
)
if Config.USE_GEMINI_TTS:
    try:
        _gemini_tts_client = GeminiTTSClient(
            model_name=Config.GEMINI_TTS_MODEL,
            default_voice=Config.GEMINI_TTS_VOICE,
            language_code=Config.GEMINI_TTS_LANGUAGE,
            audio_encoding=Config.GEMINI_TTS_AUDIO_ENCODING,
            prompt_template=Config.GEMINI_TTS_PROMPT_TEMPLATE,
        )
        logger.info(
            "Gemini TTS client initialised (model=%s, voice=%s)",
            Config.GEMINI_TTS_MODEL,
            Config.GEMINI_TTS_VOICE,
        )
    except GeminiTTSConfigurationError as exc:
        logger.warning("Gemini TTS disabled: %s", exc)
    except Exception:
        logger.exception("Failed to initialise Gemini TTS client; continuing without it")

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

# --- Private Helper Functions ---

def _clean_text_for_tts(text: str) -> str:
    return text.replace('"', '').replace('“', '').replace('”', '').strip()

def _build_gemini_tts_prompt(
    style_hint: Optional[str], speaker_slug: Optional[str], emotion: Optional[str]
) -> str:
    """Builds a detailed prompt for Gemini TTS based on context."""
    base_prompt = (Config.GEMINI_TTS_PROMPT_TEMPLATE or "").strip()
    
    context_parts = []
    if speaker_slug and speaker_slug != "narrator":
        context_parts.append(f"The speaker is the character '{speaker_slug}'.")
    else:
        context_parts.append("The speaker is the narrator.")

    if emotion:
        context_parts.append(f"The emotion of the line should be '{emotion}'.")
    
    if style_hint:
        context_parts.append(f"General style hint: '{style_hint}'.")

    if not context_parts:
        return base_prompt

    context_prompt = " ".join(context_parts)
    if base_prompt:
        # Combine, ensuring the more specific context comes after the general instruction.
        return f"{base_prompt}\n\nUse the following context:\n- {context_prompt}"
    return f"Read the following text for a children's story. Use the following context:\n- {context_prompt}"

def _resolve_gemini_extension() -> str:
    encoding = (Config.GEMINI_TTS_AUDIO_ENCODING or "").strip().lower()
    if encoding in {"linear16", "mulaw"}:
        return "wav"
    if encoding == "ogg_opus":
        return "ogg"
    return "mp3"

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

def _reading_plan_to_lines(
    reading_plan: List[dict],
    characters: List[CharacterProfile],
) -> List[str]:
    slug_to_name = {char.slug or char.name: char.name for char in characters if char.name}
    fallback_by_name = {char.name.lower(): char.name for char in characters if char.name}
    lines: List[str] = []
    for segment in reading_plan:
        text = (segment.get("text") or "").strip()
        if not text:
            continue
        segment_type = (segment.get("segment_type") or "narration").lower()
        slug = (segment.get("speaker") or "narrator").strip()
        display_name = "Narrator"
        if segment_type != "narration":
            display_name = slug_to_name.get(slug)
            if not display_name:
                display_name = fallback_by_name.get(slug.lower(), slug or "Character")
        emotion = (segment.get("emotion") or "").strip()
        if emotion:
            lines.append(f"{display_name} ({emotion}): {text}")
        else:
            lines.append(f"{display_name}: {text}")
    return lines

def _chunk_script_lines(lines: List[str], max_bytes: int = 3800) -> List[str]:
    chunks: List[str] = []
    buffer: List[str] = []
    buffer_size = 0

    def split_long_line(text: str) -> List[str]:
        segments: List[str] = []
        current_chars: List[str] = []
        current_size = 0
        for ch in text:
            encoded_len = len(ch.encode("utf-8"))
            if current_chars and current_size + encoded_len > max_bytes:
                segments.append("".join(current_chars).strip())
                current_chars = [ch]
                current_size = encoded_len
            else:
                current_chars.append(ch)
                current_size += encoded_len
        if current_chars:
            segments.append("".join(current_chars).strip())
        return [seg for seg in segments if seg]

    def flush_buffer() -> None:
        nonlocal buffer, buffer_size
        if buffer:
            chunks.append("\n".join(buffer))
            buffer = []
            buffer_size = 0

    def add_line(s: str) -> None:
        nonlocal buffer, buffer_size
        encoded_len = len(s.encode("utf-8"))
        if encoded_len > max_bytes:
            for segment in split_long_line(s):
                add_line(segment)
            return

        if buffer and buffer_size + encoded_len + 1 > max_bytes:
            flush_buffer()

        buffer.append(s)
        if buffer_size == 0:
            buffer_size = encoded_len
        else:
            buffer_size += encoded_len + 1  # account for newline separator

    for line in lines:
        add_line(line)

    flush_buffer()
    return chunks

# --- Public API ---

def create_gemini_tts(
    text: str,
    *,
    style_hint: Optional[str] = None,
    voice_name: Optional[str] = None,
    speaker_slug: Optional[str] = None,
    emotion: Optional[str] = None,
) -> bytes:
    """Generates TTS audio using the Gemini TTS preview models."""
    if _gemini_tts_client is None:
        raise RuntimeError("Gemini TTS client is not configured")
    cleaned = _clean_text_for_tts(text)
    prompt = _build_gemini_tts_prompt(style_hint, speaker_slug, emotion)
    voice_for_log = voice_name or Config.GEMINI_TTS_VOICE
    logger.info("Generating Gemini TTS chunk (%s): %s...", voice_for_log, cleaned[:40])
    return _gemini_tts_client.synthesize(
        text=cleaned,
        prompt=prompt,
        voice_name=voice_name,
    )

def get_gemini_tts_extension() -> str:
    return _resolve_gemini_extension()

def generate_paragraph_audio(
    text: str,
    *,
    speaker_slug: Optional[str],
    emotion: Optional[str],
    style_hint: Optional[str],
    request_id: str,
) -> Tuple[bytes, str]:
    """Generate audio for a single paragraph using Gemini TTS."""
    if _gemini_tts_client is None:
        raise RuntimeError("Gemini TTS client is not configured")

    cleaned = (text or "").strip()
    if not cleaned:
        raise ValueError("Paragraph text is empty")

    segment_type = "dialogue" if speaker_slug else "narration"
    preset = _resolve_voice(segment_type, speaker_slug or "narrator")

    resolved_style = style_hint or preset.get("style")
    if not style_hint and emotion:
        inferred = _map_emotion_to_style(emotion)
        if inferred:
            resolved_style = inferred

    chunks = _chunk_script_lines([cleaned])
    if not chunks:
        raise ValueError("Failed to segment paragraph text for TTS generation")

    logger.info(
        "Generating Gemini paragraph audio for request %s (chunks=%d, speaker=%s)",
        request_id,
        len(chunks),
        speaker_slug or "narrator",
    )

    audio_segments: List[bytes] = []
    for idx, chunk in enumerate(chunks, start=1):
        logger.info(
            "Gemini TTS paragraph chunk %d/%d for request %s",
            idx,
            len(chunks),
            request_id,
        )
        audio_segments.append(
            create_gemini_tts(
                chunk, 
                style_hint=resolved_style, 
                speaker_slug=speaker_slug, 
                emotion=emotion
            )
        )

    extension = _resolve_gemini_extension()
    if len(audio_segments) == 1:
        return audio_segments[0], extension

    if extension == "wav":
        return _merge_wav_segments(audio_segments), "wav"

    logger.warning(
        "Concatenating %d Gemini paragraph audio chunks without re-encoding (encoding=%s)",
        len(audio_segments),
        extension,
    )
    return b"".join(audio_segments), extension

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
