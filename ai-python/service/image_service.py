
import base64
import logging
from io import BytesIO
from textwrap import dedent
from typing import List, Optional, Tuple

from PIL import Image
from openai import OpenAI

from config import Config
from schemas import CharacterProfile, CharacterVisual
from service.image_providers import (
    GeminiImageProvider,
    GeminiProviderConfig,
    ImageProvider,
    ImageProviderError,
    OpenAIImageProvider,
    OpenAIProviderConfig,
)

logger = logging.getLogger(__name__)

# --- Service-level client initialization ---

_openai_client = OpenAI(api_key=Config.OPENAI_API_KEY)

_base_image_style = dedent(
    '''
    Illustration style guide: minimalistic watercolor with soft pastel palette,
    simple shapes, gentle lighting, subtle textures, and limited background detail.
    Maintain consistent character proportions (round face, expressive large eyes, short limbs)
    and identical costume colors across every scene in the same story. Avoid clutter and stick to two
    or three key props.
    '''
).strip()

_openai_image_provider = OpenAIImageProvider(
    client=_openai_client,
    config=OpenAIProviderConfig(
        model=Config.OPENAI_IMAGE_MODEL,
        size=Config.OPENAI_IMAGE_SIZE,
        quality=Config.OPENAI_IMAGE_QUALITY,
    ),
)

_gemini_image_provider: Optional[ImageProvider] = None
_prefer_gemini_image = Config.USE_GEMINI_IMAGE


def _parse_dimensions(size: str) -> Optional[Tuple[int, int]]:
    try:
        width, height = size.lower().split("x", 1)
        return int(width.strip()), int(height.strip())
    except Exception:  # pragma: no cover - defensive parsing
        return None


if _prefer_gemini_image:
    try:
        if not Config.GEMINI_API_KEY:
            raise ImageProviderError("GEMINI_API_KEY is not configured.")

        dimensions = _parse_dimensions(Config.OPENAI_IMAGE_SIZE)
        aspect_ratio = None
        if dimensions:
            w, h = dimensions
            if w and h:
                try:
                    from math import gcd

                    g = gcd(w, h)
                    aspect_ratio = f"{w // g}:{h // g}"
                except Exception:  # pragma: no cover - math fallback
                    aspect_ratio = f"{w}:{h}"

        _gemini_image_provider = GeminiImageProvider(
            api_key=Config.GEMINI_API_KEY,
            config=GeminiProviderConfig(
                model=Config.GEMINI_IMAGE_MODEL,
                image_dimensions=dimensions,
                aspect_ratio=aspect_ratio,
                output_mime_type="image/png",
                number_of_images=1,
            ),
        )
        logger.info(
            "Gemini image provider initialised (model=%s)",
            Config.GEMINI_IMAGE_MODEL,
        )
    except ImageProviderError as exc:
        _prefer_gemini_image = False
        logger.warning(
            "Gemini image provider disabled, falling back to OpenAI: %s",
            exc,
        )

if not _prefer_gemini_image:
    logger.info("Using OpenAI image provider (model=%s)", Config.OPENAI_IMAGE_MODEL)


def _get_image_provider() -> ImageProvider:
    if _prefer_gemini_image and _gemini_image_provider is not None:
        return _gemini_image_provider
    return _openai_image_provider

# --- Public API ---

def generate_image(
    text: str,
    request_id: str,
    art_style: Optional[str] = None,
    character_visuals: Optional[List[CharacterVisual]] = None,
    character_images: Optional[List[CharacterProfile]] = None, # Kept for fallback
) -> str:
    """
    Generates an image based on the provided text and character descriptions.
    It attempts to use the preferred Google provider (Imagen) first, and falls back
    to OpenAI (DALL-E) if the primary provider fails.
    Returns a base64 encoded string of the image.
    """
    style_guide = art_style or _base_image_style
    if not art_style:
        logger.warning("No art_style supplied for %s; falling back to default style guide.", request_id)

    prompt = dedent(
        f"""
        {style_guide}
        Scene description: {text}
        """
    ).strip()

    character_section_added = False
    if character_visuals:
        descriptions = [f"- {visual.name}: {visual.visual_description}" for visual in character_visuals if visual.visual_description]
        if descriptions:
            prompt += "\n\nCharacters to include (follow these descriptions strictly):\n" + "\n".join(descriptions)
            character_section_added = True
    elif character_images:
        descriptions = []
        for profile in character_images:
            details = []
            if profile.persona:
                details.append(f"persona: {profile.persona}")
            if profile.prompt_keywords:
                details.append(f"visual cues: {profile.prompt_keywords}")
            if profile.catchphrase:
                details.append(f"catchphrase: {profile.catchphrase}")
            detail_text = ", ".join(details) or "describe in warm, child-friendly style"
            descriptions.append(f"- {profile.name}: {detail_text}")
        if descriptions:
            prompt += "\n\nCharacters to include (derive consistent appearance from these cues):\n" + "\n".join(descriptions)
            character_section_added = True

    if not character_section_added:
        logger.warning("No character visuals were provided for %s; image prompt may lack character guidance.", request_id)

    logger.debug("Image prompt for %s:\n%s", request_id, prompt)

    # Define providers in order of preference
    providers: List[Tuple[str, ImageProvider]] = []
    if _prefer_gemini_image and _gemini_image_provider is not None:
        providers.append(("Gemini", _gemini_image_provider))
    providers.append(("OpenAI", _openai_image_provider))

    last_error: Optional[Exception] = None
    image_bytes: Optional[bytes] = None

    for provider_name, provider in providers:
        logger.info(f"Attempting image generation via {provider_name} for request_id {request_id}")
        try:
            image_bytes = provider.generate(prompt=prompt, request_id=request_id)
            logger.info(f"{provider_name} provider succeeded.")
            break  # Success, exit the loop
        except ImageProviderError as exc:
            last_error = exc
            logger.warning(
                "%s image provider failed for %s: %s",
                provider_name,
                request_id,
                exc,
            )
            # Continue to the next provider
            continue

    if image_bytes is None:
        raise ImageProviderError("All image providers failed.") from last_error

    # Resize and encode the image
    image = Image.open(BytesIO(image_bytes)).convert("RGB")
    image = image.resize((512, 512), Image.LANCZOS)
    buffer = BytesIO()
    image.save(buffer, format="PNG")
    encoded = base64.b64encode(buffer.getvalue()).decode("utf-8")

    return encoded
