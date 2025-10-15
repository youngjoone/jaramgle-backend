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
    def generate(self, *, prompt: str, request_id: str, image_bytes: Optional[bytes] = None) -> bytes:
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

    def generate(self, *, prompt: str, request_id: str, image_bytes: Optional[bytes] = None) -> bytes:
        if image_bytes:
            logger.warning("OpenAI DALL-E does not support image prompting; ignoring provided image_bytes.")
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
    @staticmethod
    def _normalize_model_name(model: str) -> str:
        return model.strip() if model else model

    def __init__(self, api_key: str, config: GeminiProviderConfig):
        if not api_key:
            raise ImageProviderError("Gemini API key is not configured")

        self._config = config
        self._api_key = api_key
        self._client = None
        self._mode = "rest"

        try:
            from google import genai as google_genai  # type: ignore

            self._client = google_genai.Client(api_key=api_key)
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

    def generate(self, *, prompt: str, request_id: str, image_bytes: Optional[bytes] = None) -> bytes:
        try:
            if self._mode == "client" and self._client is not None:
                return self._generate_with_client(prompt=prompt, image_bytes=image_bytes)
            return self._generate_via_rest(prompt=prompt, image_bytes=image_bytes)
        except ImageProviderError:
            raise
        except Exception as exc:  # pragma: no cover - defensive guard
            logger.exception("Gemini image generation failed: %s", exc)
            raise ImageProviderError(str(exc)) from exc

    def _generate_with_client(self, *, prompt: str, image_bytes: Optional[bytes] = None) -> bytes:
        if self._client is None:
            raise ImageProviderError("Gemini client unavailable")

        try:
            from google.genai import types  # type: ignore
        except Exception as exc:  # pragma: no cover - dependency guard
            raise ImageProviderError(f"google.genai types unavailable: {exc}") from exc

        if image_bytes:
            logger.warning("Gemini generate_images does not yet support image prompts via this client; ignoring provided image bytes.")

        model_name = self._normalize_model_name(self._config.model)
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

        try:
            config_obj = types.GenerateImagesConfig(**config_kwargs)  # type: ignore[attr-defined]
            response = self._client.models.generate_images(  # type: ignore[union-attr]
                model=model_name,
                prompt=prompt,
                config=config_obj,
            )
        except TypeError as exc:
            logger.warning(
                "Gemini client generate_images signature mismatch (%s); falling back to REST", exc
            )
            # Fall back to REST implementation to maximise compatibility across SDK versions
            return self._generate_via_rest(prompt=prompt)

        images = getattr(response, "generated_images", None) or []
        if not images:
            feedback = getattr(response, "prompt_feedback", None)
            raise ImageProviderError(
                f"Gemini response did not contain image data. feedback={feedback}"
            )

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

    def _generate_via_rest(self, *, prompt: str, image_bytes: Optional[bytes] = None) -> bytes:
        # Lazy import to avoid dependency for users that do not need Gemini.
        import requests

        model_name = self._normalize_model_name(self._config.model)
        model_path = model_name if model_name.startswith("models/") else f"models/{model_name}"
        url = (
            "https://generativelanguage.googleapis.com/v1beta/"
            f"{model_path}:predict"
        )
        params = {"key": self._api_key}

        if image_bytes:
            logger.warning(
                "Gemini REST API image generation currently ignores reference image bytes."
            )

        body = {"instances": [{"prompt": prompt}]}

        parameters: dict = {}
        requested_images = max(1, int(self._config.number_of_images or 1))
        parameters["sampleCount"] = requested_images

        aspect_ratio = self._config.aspect_ratio
        if not aspect_ratio and self._config.image_dimensions:
            width, height = self._config.image_dimensions
            if width and height:
                aspect_ratio = f"{width}:{height}"
        if aspect_ratio:
            parameters["aspectRatio"] = aspect_ratio

        if self._config.output_mime_type:
            parameters.setdefault("outputOptions", {})["mimeType"] = self._config.output_mime_type

        if parameters:
            body["parameters"] = parameters

        response = requests.post(url, params=params, json=body, timeout=60)
        if response.status_code >= 400:
            try:
                payload = response.json()
            except Exception:
                payload = response.text
            logger.error(
                "Gemini REST API error %s: %s", response.status_code, payload
            )
            raise ImageProviderError(f"REST error {response.status_code}: {payload}")

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


@dataclass
class ImagenProviderConfig:
    model: str
    project_id: str
    location: str
    number_of_images: int = 1


class ImagenImageProvider(ImageProvider):
    """Image provider for Google's Imagen models via Vertex AI."""

    def __init__(self, config: ImagenProviderConfig):
        self._config = config
        try:
            from google.cloud import aiplatform
            from vertexai.preview.vision_models import ImageGenerationModel

            aiplatform.init(project=config.project_id, location=config.location)
            self._model = ImageGenerationModel.from_pretrained(config.model)
            logger.info("Imagen provider initialised for model %s", config.model)
        except ImportError:
            raise ImageProviderError(
                "ImagenImageProvider requires google-cloud-aiplatform to be installed."
            )
        except Exception as exc:
            logger.exception("Failed to initialise Vertex AI: %s", exc)
            raise ImageProviderError(
                "Failed to initialise Vertex AI. Ensure you have authenticated via 'gcloud auth application-default login'"
            ) from exc

    def generate(self, *, prompt: str, request_id: str, image_bytes: Optional[bytes] = None) -> bytes:
        try:
            generation_kwargs = {
                "prompt": prompt,
                "number_of_images": self._config.number_of_images,
            }
            if image_bytes:
                from vertexai.preview.vision_models import Image

                generation_kwargs["image_input"] = Image(image_bytes)

            images = self._model.generate_images(**generation_kwargs)
            # The response contains a list of Image objects
            if not images:
                raise ImageProviderError("Imagen response contained no images.")
            
            # The _image_bytes property is already in bytes format
            return images[0]._image_bytes
        except Exception as exc:
            logger.exception("Imagen image generation failed: %s", exc)
            raise ImageProviderError(str(exc)) from exc


@dataclass
class Gemini25FlashProviderConfig:
    model: str
    project_id: str
    location: str
    candidate_count: int = 1


class Gemini25FlashImageProvider(ImageProvider):
    """Image provider for Google's multimodal Gemini models (e.g., Gemini 2.5 Flash Image) via Vertex AI."""

    def __init__(self, config: Gemini25FlashProviderConfig):
        self._config = config
        try:
            from google import genai

            self._client = genai.Client(
                vertexai=True, project=config.project_id, location=config.location
            )
            logger.info(
                "Gemini 2.5 Flash provider initialised for model %s", config.model
            )
        except ImportError:
            raise ImageProviderError(
                "Gemini25FlashImageProvider requires google-generativeai to be installed."
            )
        except Exception as exc:
            logger.exception("Failed to initialise Gemini 2.5 Flash provider: %s", exc)
            raise ImageProviderError(
                "Failed to initialise Gemini 2.5 Flash provider. Ensure you have authenticated via 'gcloud auth application-default login'"
            ) from exc

    def generate(self, *, prompt: str, request_id: str, image_bytes: Optional[bytes] = None) -> bytes:
        try:
            from google.genai.types import GenerateContentConfig
            from PIL import Image
            from io import BytesIO

            contents = [prompt]
            if image_bytes:
                img = Image.open(BytesIO(image_bytes))
                contents.append(img)

            generation_config = GenerateContentConfig(
                response_modalities=["IMAGE"],
                candidate_count=self._config.candidate_count,
            )

            response = self._client.models.generate_content(
                model=self._config.model,
                contents=contents,
                config=generation_config,
            )

            if not response.candidates:
                raise ImageProviderError("Gemini 2.5 Flash response contained no candidates.")

            # Find the first part that contains image data
            for part in response.candidates[0].content.parts:
                if part.inline_data and part.inline_data.data:
                    return part.inline_data.data

            raise ImageProviderError("Gemini 2.5 Flash response did not contain image data.")

        except Exception as exc:
            logger.exception("Gemini 2.5 Flash image generation failed: %s", exc)
            raise ImageProviderError(str(exc)) from exc
