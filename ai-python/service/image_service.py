
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

_openai_image_provider = OpenAIImageProvider(
    client=_openai_client,
    config=OpenAIProviderConfig(
        model=Config.OPENAI_IMAGE_MODEL,
        size=Config.OPENAI_IMAGE_SIZE,
        quality=Config.OPENAI_IMAGE_QUALITY,
    ),
)

_gemini_image_provider: Optional[ImageProvider] = None
_prefer_gemini = Config.USE_GEMINI_IMAGE

if _prefer_gemini:
    dimensions = _parse_image_dimensions(Config.OPENAI_IMAGE_SIZE)
    try:
        _gemini_image_provider = GeminiImageProvider(
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
        _prefer_gemini = False
        logger.warning(
            "Gemini image provider disabled, falling back to OpenAI: %s",
            exc,
        )

if not _prefer_gemini:
    logger.info(
        "Using OpenAI image provider (model=%s)", Config.OPENAI_IMAGE_MODEL
    )


def _get_image_provider() -> ImageProvider:
    if _prefer_gemini and _gemini_image_provider is not None:
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
    It uses the preferred provider (Gemini) and falls back to OpenAI if needed.
    Returns a base64 encoded string of the image.
    """
    style_guide = art_style or _base_image_style

    prompt = dedent(
        f"""
        {style_guide}
        Scene description: {text}
        """
    ).strip()

    if character_visuals:
        descriptions = []
        for visual in character_visuals:
            descriptions.append(f"- {visual.name}: {visual.visual_description}")
        if descriptions:
            prompt += "\n\nCharacters to include (follow these descriptions strictly):\n" + "\n".join(descriptions)
    
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

    # For now, we just use the primary provider. A more robust implementation could add fallback.
    primary_provider = _get_image_provider()
    provider_name = "Gemini" if primary_provider is _gemini_image_provider else "OpenAI"
    logger.info(
        "Generating image via %s for request_id %s", provider_name, request_id
    )

    try:
        image_bytes = primary_provider.generate(
            prompt=prompt,
            request_id=request_id,
        )
    except ImageProviderError as exc:
        logger.warning(
            "%s image provider failed for %s: %s",
            provider_name,
            request_id,
            exc,
        )
        # In a full implementation, a fallback to another provider could be added here.
        raise

    if image_bytes is None:
        raise ImageProviderError("Image generation failed for all providers")

    # Resize and encode the image
    image = Image.open(BytesIO(image_bytes)).convert("RGB")
    image = image.resize((512, 512), Image.LANCZOS)
    buffer = BytesIO()
    image.save(buffer, format="PNG")
    encoded = base64.b64encode(buffer.getvalue()).decode("utf-8")

    return encoded
