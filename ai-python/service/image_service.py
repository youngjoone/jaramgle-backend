
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
    ImageProvider,
    ImageProviderError,
    OpenAIImageProvider,
    OpenAIProviderConfig,
    ImagenImageProvider, # Use the new Imagen provider
    ImagenProviderConfig,
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

_google_image_provider: Optional[ImageProvider] = None
_prefer_google_image = Config.USE_GEMINI_IMAGE # This flag now controls Imagen vs OpenAI

if _prefer_google_image:
    try:
        if not Config.GOOGLE_PROJECT_ID:
            raise ImageProviderError("GOOGLE_PROJECT_ID is not set in config.")
        
        _google_image_provider = ImagenImageProvider(
            config=ImagenProviderConfig(
                model=Config.GEMINI_IMAGE_MODEL, # This now holds the Imagen model name
                project_id=Config.GOOGLE_PROJECT_ID,
                location=Config.GOOGLE_LOCATION,
            )
        )
        logger.info(
            "Imagen image provider initialised (model=%s)",
            Config.GEMINI_IMAGE_MODEL,
        )
    except ImageProviderError as exc:
        _prefer_google_image = False
        logger.warning(
            "Google Imagen provider disabled, falling back to OpenAI: %s",
            exc,
        )

if not _prefer_google_image:
    logger.info(
        "Using OpenAI image provider (model=%s)", Config.OPENAI_IMAGE_MODEL
    )


def _get_image_provider() -> ImageProvider:
    if _prefer_google_image and _google_image_provider is not None:
        return _google_image_provider
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

    # Define providers in order of preference
    providers: List[Tuple[str, ImageProvider]] = []
    if _prefer_google_image and _google_image_provider is not None:
        providers.append(("Imagen", _google_image_provider))
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
