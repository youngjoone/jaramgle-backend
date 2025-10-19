import logging
from typing import Optional

try:
    from google.cloud import texttospeech
except ImportError as exc:  # pragma: no cover - optional dependency
    texttospeech = None
    _import_error = exc
else:
    _import_error = None


class GeminiTTSConfigurationError(Exception):
    """Raised when the Gemini TTS client cannot be configured."""


class GeminiTTSClient:
    """Wrapper around the Google Cloud Text-to-Speech client for Gemini models."""

    _ENCODING_MAP = {
        "MP3": "MP3",
        "LINEAR16": "LINEAR16",
        "OGG_OPUS": "OGG_OPUS",
        "MULAW": "MULAW",
    }

    def __init__(
        self,
        *,
        model_name: str,
        default_voice: str,
        language_code: str = "en-US",
        audio_encoding: str = "MP3",
        prompt_template: str = "",
    ) -> None:
        if texttospeech is None:
            raise GeminiTTSConfigurationError(
                "google-cloud-texttospeech package is not installed"
            ) from _import_error

        if not model_name:
            raise GeminiTTSConfigurationError("Gemini TTS model is not configured")

        self._logger = logging.getLogger(__name__)
        self._client = texttospeech.TextToSpeechClient()
        self._model_name = model_name
        self._default_voice = default_voice or "Charon"
        self._language_code = language_code or "en-US"
        self._audio_encoding = self._resolve_audio_encoding(audio_encoding)
        self._prompt_template = prompt_template or ""

    def _resolve_audio_encoding(self, label: Optional[str]):
        normalized = (label or "").strip().upper()
        encoding_name = self._ENCODING_MAP.get(normalized)
        if encoding_name is None:
            self._logger.warning(
                "Unknown Gemini TTS audio encoding '%s', defaulting to MP3", label
            )
            encoding_name = "MP3"
        return getattr(texttospeech.AudioEncoding, encoding_name)

    def synthesize(
        self,
        *,
        text: str,
        prompt: Optional[str] = None,
        voice_name: Optional[str] = None,
        language_code: Optional[str] = None,
        audio_encoding: Optional[str] = None,
    ) -> bytes:
        if not text or not text.strip():
            raise ValueError("Text for Gemini TTS synthesis cannot be empty")

        synthesis_input = texttospeech.SynthesisInput(text=text)

        effective_prompt = prompt if prompt is not None else self._prompt_template
        if effective_prompt:
            try:
                synthesis_input.prompt = effective_prompt
            except AttributeError:
                self._logger.warning(
                    "Installed google-cloud-texttospeech version does not support the prompt parameter; continuing without stylistic prompt.",
                )

        voice_params = texttospeech.VoiceSelectionParams(
            language_code=language_code or self._language_code,
            name=voice_name or self._default_voice,
        )

        model_assigned = False
        for attr in ("model", "model_name"):
            try:
                setattr(voice_params, attr, self._model_name)
                model_assigned = True
                break
            except AttributeError:
                continue
        if not model_assigned:
            self._logger.warning(
                "Gemini TTS voice parameters do not expose a model field; using voice without explicit model binding.",
            )

        effective_encoding = (
            self._resolve_audio_encoding(audio_encoding)
            if audio_encoding
            else self._audio_encoding
        )
        audio_config = texttospeech.AudioConfig(audio_encoding=effective_encoding)

        response = self._client.synthesize_speech(
            input=synthesis_input,
            voice=voice_params,
            audio_config=audio_config,
        )

        audio_content = getattr(response, "audio_content", None)
        if not audio_content:
            raise RuntimeError("Gemini TTS response did not include audio content")
        return audio_content
