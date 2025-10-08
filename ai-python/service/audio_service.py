import logging
import wave
from html import escape
from io import BytesIO
from typing import Dict, List, Optional, Tuple

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