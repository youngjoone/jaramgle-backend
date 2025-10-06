import base64
import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Optional, Tuple

from openai import OpenAI

logger = logging.getLogger(__name__)


class ImageProviderError(RuntimeError):
    """Wrap provider-specific errors so callers can handle them uniformly."""


class ImageProvider(ABC):
    @abstractmethod
    def generate(self, *, prompt: str, request_id: str) -> bytes:
        """Return raw image bytes."""


@dataclass
class OpenAIProviderConfig:
    model: str
    size: str
    quality: str


class OpenAIImageProvider(ImageProvider):
    def __init__(self, client: OpenAI, config: OpenAIProviderConfig):
        self._client = client
        self._config = config

    def generate(self, *, prompt: str, request_id: str) -> bytes:
        try:
            response = self._client.images.generate(
                model=self._config.model,
                prompt=prompt,
                size=self._config.size,
                quality=self._config.quality,
                n=1,
                user=request_id or "anon",
            )
        except Exception as exc:  # pragma: no cover - SDK exception surface
            logger.exception("OpenAI image generation failed: %s", exc)
            raise ImageProviderError(str(exc)) from exc

        raw_data = response.data[0]
        image_base64 = getattr(raw_data, "b64_json", None)

        if image_base64 is None:
            image_url = getattr(raw_data, "url", None)
            if not image_url:
                raise ImageProviderError("OpenAI response missing both b64_json and url fields")

            import requests

            try:
                download = requests.get(image_url, timeout=30)
                download.raise_for_status()
            except Exception as exc:  # pragma: no cover - network failures
                logger.exception("Failed to download OpenAI image: %s", exc)
                raise ImageProviderError(str(exc)) from exc
            return download.content

        try:
            return base64.b64decode(image_base64)
        except Exception as exc:  # pragma: no cover - decoding guardrail
            raise ImageProviderError("Failed to decode OpenAI image payload") from exc


@dataclass
class GeminiProviderConfig:
    model: str
    image_dimensions: Optional[Tuple[int, int]] = None
    aspect_ratio: Optional[str] = None
    output_mime_type: str = "image/png"
    number_of_images: int = 1


class GeminiImageProvider(ImageProvider):
    def __init__(self, api_key: str, config: GeminiProviderConfig):
        if not api_key:
            raise ImageProviderError("Gemini API key is not configured")

        self._config = config
        self._api_key = api_key
        self._client = None
        self._image_config_cls = None
        self._mode = "rest"

        try:
            from google import genai as google_genai  # type: ignore
            from google.genai.types import ImageGenerationConfig  # type: ignore

            self._client = google_genai.Client(api_key=api_key)
            self._image_config_cls = ImageGenerationConfig
            self._mode = "client"
            logger.info(
                "Gemini image provider initialised (client library) for model=%s",
                self._config.model,
            )
        except Exception as exc:  # pragma: no cover - dependency guard
            # Fall back to REST API usage if the official client is unavailable.
            logger.info(
                "Gemini client library unavailable (%s); falling back to REST calls",
                exc,
            )

    def generate(self, *, prompt: str, request_id: str) -> bytes:
        try:
            if self._mode == "client" and self._client is not None:
                return self._generate_with_client(prompt=prompt)
            return self._generate_via_rest(prompt=prompt)
        except ImageProviderError:
            raise
        except Exception as exc:  # pragma: no cover - defensive guard
            logger.exception("Gemini image generation failed: %s", exc)
            raise ImageProviderError(str(exc)) from exc

    def _generate_with_client(self, *, prompt: str) -> bytes:
        config_kwargs = {
            "number_of_images": max(1, int(self._config.number_of_images or 1)),
            "output_mime_type": self._config.output_mime_type,
        }

        aspect_ratio = self._config.aspect_ratio
        if not aspect_ratio and self._config.image_dimensions:
            width, height = self._config.image_dimensions
            if width and height:
                aspect_ratio = f"{width}:{height}"
        if aspect_ratio:
            config_kwargs["aspect_ratio"] = aspect_ratio

        image_config = None
        if self._image_config_cls is not None:
            image_config = self._image_config_cls(**config_kwargs)

        response = self._client.models.generate_images(  # type: ignore[union-attr]
            model=self._config.model,
            prompt=prompt,
            config=image_config,
        )

        images = getattr(response, "generated_images", None) or []
        if not images:
            raise ImageProviderError("Gemini response did not contain image data")

        first = images[0]
        image = getattr(first, "image", None)
        if image is None:
            raise ImageProviderError("Gemini image payload missing image field")

        image_bytes = getattr(image, "image_bytes", None)
        if isinstance(image_bytes, (bytes, bytearray)):
            return bytes(image_bytes)

        if isinstance(image_bytes, str):
            return base64.b64decode(image_bytes)

        raise ImageProviderError("Gemini image payload missing bytes")

    def _generate_via_rest(self, *, prompt: str) -> bytes:
        # Lazy import to avoid dependency for users that do not need Gemini.
        import requests

        url = (
            "https://generativelanguage.googleapis.com/v1beta/models/"
            f"{self._config.model}:generateImages"
        )
        params = {"key": self._api_key}

        body = {"prompt": {"text": prompt}}
        config: dict = {}

        requested_images = max(1, int(self._config.number_of_images or 1))
        config["numberOfImages"] = requested_images

        if self._config.output_mime_type:
            config["outputMimeType"] = self._config.output_mime_type

        aspect_ratio = self._config.aspect_ratio
        if not aspect_ratio and self._config.image_dimensions:
            width, height = self._config.image_dimensions
            if width and height:
                aspect_ratio = f"{width}:{height}"
        if aspect_ratio:
            config["aspectRatio"] = aspect_ratio

        if config:
            body["imageGenerationConfig"] = config

        response = requests.post(url, params=params, json=body, timeout=60)
        if response.status_code >= 400:
            logger.error(
                "Gemini REST API error %s: %s", response.status_code, response.text
            )
            raise ImageProviderError(response.text)

        payload = response.json()
        images = (
            payload.get("generatedImages")
            or payload.get("generated_images")
            or []
        )
        if not images:
            raise ImageProviderError("Gemini response did not contain image data")

        first = images[0]
        image = first.get("image") if isinstance(first, dict) else None
        if not image:
            raise ImageProviderError("Gemini image payload missing image field")

        data = (
            image.get("imageBytes")
            or image.get("image_bytes")
            or image.get("bytesBase64Encoded")
        )
        if not data:
            raise ImageProviderError("Gemini image payload missing bytes")

        if isinstance(data, str):
            return base64.b64decode(data)
        if isinstance(data, (bytes, bytearray)):
            return bytes(data)

        raise ImageProviderError("Gemini image bytes format is not supported")
