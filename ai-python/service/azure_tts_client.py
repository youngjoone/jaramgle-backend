import logging
import os
import tempfile
from typing import Optional

try:
    import azure.cognitiveservices.speech as speechsdk
except ImportError as exc:  # pragma: no cover - handled at runtime
    speechsdk = None
    _import_error = exc
else:
    _import_error = None


class AzureTTSConfigurationError(Exception):
    """Raised when Azure TTS cannot be configured."""


class AzureTTSClient:
    """Thin wrapper around the Azure Speech SDK for SSML synthesis."""

    _OUTPUT_FORMAT_MAP = {
        "riff-16khz-16bit-mono-pcm": "Riff16Khz16BitMonoPcm",
        "riff-24khz-16bit-mono-pcm": "Riff24Khz16BitMonoPcm",
        "riff-48khz-16bit-mono-pcm": "Riff48Khz16BitMonoPcm",
        "audio-16khz-64kbitrate-mono-mp3": "Audio16Khz64KBitRateMonoMp3",
        "audio-24khz-160kbitrate-mono-mp3": "Audio24Khz160KBitRateMonoMp3",
    }

    def __init__(self, key: str, region: str, output_format: str = "riff-24khz-16bit-mono-pcm"):
        if speechsdk is None:
            raise AzureTTSConfigurationError(
                "azure-cognitiveservices-speech package is not installed"
            ) from _import_error

        if not key:
            raise AzureTTSConfigurationError("AZURE_SPEECH_KEY is missing")
        if not region:
            raise AzureTTSConfigurationError("AZURE_SPEECH_REGION is missing")

        self._logger = logging.getLogger(__name__)
        self._speech_config = speechsdk.SpeechConfig(subscription=key, region=region)
        self._set_output_format(output_format)

    def _set_output_format(self, label: str) -> None:
        normalized = (label or "").strip().lower()
        mapped = self._OUTPUT_FORMAT_MAP.get(normalized)
        if mapped is None:
            mapped = self._OUTPUT_FORMAT_MAP["riff-24khz-16bit-mono-pcm"]
            self._logger.warning(
                "Unknown Azure TTS output format '%s', falling back to %s",
                label,
                mapped,
            )
        output_enum = getattr(speechsdk.SpeechSynthesisOutputFormat, mapped)
        self._speech_config.set_speech_synthesis_output_format(output_enum)

    def synthesize_ssml(self, ssml: str) -> bytes:
        if not ssml.strip():
            raise ValueError("SSML payload is empty")

        fd, temp_path = tempfile.mkstemp(suffix=".wav")
        os.close(fd)
        try:
            audio_config = speechsdk.audio.AudioOutputConfig(filename=temp_path)
            synthesizer = speechsdk.SpeechSynthesizer(
                speech_config=self._speech_config,
                audio_config=audio_config,
            )

            result = synthesizer.speak_ssml_async(ssml).get()

            if result.reason == speechsdk.ResultReason.SynthesizingAudioCompleted:
                with open(temp_path, "rb") as handle:
                    return handle.read()

            if result.reason == speechsdk.ResultReason.Canceled:
                cancellation = result.cancellation_details
                error_details = getattr(cancellation, "error_details", "")
                raise RuntimeError(
                    f"Azure TTS canceled: {cancellation.reason}. {error_details}"
                )

            raise RuntimeError("Azure TTS synthesis failed with unknown reason")
        finally:
            try:
                os.remove(temp_path)
            except OSError:
                self._logger.warning("Failed to remove temporary Azure audio file %s", temp_path)
