
import base64
import logging
import os
import re
from io import BytesIO
from textwrap import dedent
from typing import List, Optional, Tuple
from urllib.parse import urlparse, unquote

from PIL import Image
from openai import OpenAI

from config import Config
from schemas import CharacterProfile, CharacterVisual
from service.image_providers import (
    GeminiImageProvider,
    GeminiProviderConfig,
    ImageProvider,
    ImageProviderError,
    ImagenImageProvider,
    ImagenProviderConfig,
    Gemini25FlashImageProvider,
    Gemini25FlashProviderConfig,
    OpenAIImageProvider,
    OpenAIProviderConfig,
)

logger = logging.getLogger(__name__)

# --- Service-level client initialization ---

_openai_client = OpenAI(api_key=Config.OPENAI_API_KEY)

_base_image_style = dedent(
    '''
    You are a professional children's storybook illustrator, specializing in classic fairy tale aesthetics. Your task is to create an image that perfectly matches the provided scene description from a children's story.
    Illustration style guide: whimsical, enchanting, classic fairy tale illustration, minimalistic watercolor with a soft pastel palette,
    simple shapes, gentle lighting, subtle textures, and limited background detail.
    Crucially, maintain absolute consistency in character appearance, proportions (round face, expressive large eyes, short limbs),
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
_prefer_google_image = Config.USE_GEMINI_IMAGE


def _parse_dimensions(size: str) -> Optional[Tuple[int, int]]:
    try:
        width, height = size.lower().split("x", 1)
        return int(width.strip()), int(height.strip())
    except Exception:  # pragma: no cover - defensive parsing
        return None


if _prefer_google_image:
    try:
        if not Config.GOOGLE_PROJECT_ID or not Config.GOOGLE_LOCATION:
            raise ImageProviderError(
                "GOOGLE_PROJECT_ID and GOOGLE_LOCATION must be configured for Gemini 2.5 Flash."
            )

        _google_image_provider = Gemini25FlashImageProvider(
            config=Gemini25FlashProviderConfig(
                model=Config.GEMINI_IMAGE_MODEL,
                project_id=Config.GOOGLE_PROJECT_ID,
                location=Config.GOOGLE_LOCATION,
                candidate_count=1,
            ),
        )
        logger.info(
            "Google Gemini 2.5 Flash image provider initialised (model=%s)",
            Config.GEMINI_IMAGE_MODEL,
        )
    except ImageProviderError as exc:
        _prefer_google_image = False
        logger.warning(
            "Google Gemini 2.5 Flash provider disabled, falling back to OpenAI: %s",
            exc,
        )

if not _prefer_google_image:
    logger.info("Using OpenAI image provider (model=%s)", Config.OPENAI_IMAGE_MODEL)


def _get_image_provider() -> ImageProvider:
    if _prefer_google_image and _google_image_provider is not None:
        return _google_image_provider
    return _openai_image_provider


def _strip_dialogue(raw_text: Optional[str]) -> str:
    if not raw_text:
        return ""
    cleaned = re.sub(r"“[^”]*”", "", raw_text)
    cleaned = re.sub(r"\"[^\"]*\"", "", cleaned)
    cleaned = re.sub(r"'[^']*'", "", cleaned)
    cleaned = cleaned.replace("\n", " ")
    cleaned = re.sub(r"\s{2,}", " ", cleaned)
    return cleaned.strip()

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
    It attempts to use the preferred Google provider first and falls back to OpenAI.
    Returns a base64 encoded string of the image.
    """
    # 1. Define Style
    style_guide = art_style or _base_image_style
    if not art_style:
        logger.warning("No art_style supplied for %s; falling back to default style guide.", request_id)

    # 2. Define Characters
    character_descriptions = []
    if character_visuals:
        for visual in character_visuals:
            if visual.visual_description:
                # Extract just the persona/personality part, as visuals come from the reference image
                persona_match = re.search(r"Persona: ([^|]+)", visual.visual_description, re.IGNORECASE)
                persona = persona_match.group(1).strip() if persona_match else visual.name
                character_descriptions.append(f"- {visual.name}: {persona}")

    character_section = ""
    if character_descriptions:
        character_section = "\n\nCharacters in this scene:\n" + "\n".join(character_descriptions)

    # 3. Define Scene
    scene_summary = _strip_dialogue(text)
    if not scene_summary:
        scene_summary = "A key moment in the story with characters interacting."

    # 4. Define Rules
    rules = dedent("""
        - Show characters mid-action with expressive body language and clear emotions.
        - Vary the composition and background to make the setting feel alive.
        - Maintain character appearance from the reference image.
        - Absolutely no speech bubbles, captions, or text of any kind.
    """).strip()

    # 5. Assemble the final prompt
    prompt = dedent(
        f"""
        {style_guide}
        {character_section}

        Illustrate the moment: {scene_summary}

        Rules:
        {rules}
        """
    ).strip()

    logger.info("Image generation prompt for %s: %s", request_id, prompt)

    # --- The rest of the function remains the same ---

    reference_image_bytes: Optional[bytes] = None
    if character_visuals:
        for visual in character_visuals:
            if visual.image_url:
                try:
                    parsed = urlparse(visual.image_url)
                    if parsed.scheme == "file":
                        local_path = unquote(parsed.path)
                        if os.name == "nt" and local_path.startswith("/"):
                            local_path = local_path.lstrip("/")
                        if not local_path:
                            logger.warning("Empty local path extracted from %s", visual.image_url)
                            continue
                        with open(local_path, "rb") as f:
                            reference_image_bytes = f.read()
                        logger.info(f"Using local image file as reference: {local_path}")
                        break
                    else:
                        logger.warning(
                            "Non-file image_url provided but not yet supported for direct image prompting: %s",
                            visual.image_url,
                        )
                except FileNotFoundError:
                    logger.warning(f"Reference image file not found: {local_path}")
                except Exception as e:
                    logger.error(f"Error reading reference image file {visual.image_url}: {e}")

    providers: List[Tuple[str, ImageProvider]] = []
    if _prefer_google_image and _google_image_provider is not None:
        providers.append(("Google Gemini 2.5 Flash", _google_image_provider))
    providers.append(("OpenAI", _openai_image_provider))

    last_error: Optional[Exception] = None
    image_bytes: Optional[bytes] = None

    for provider_name, provider in providers:
        logger.info(f"Attempting image generation via {provider_name} for request_id {request_id}")
        try:
            image_bytes = provider.generate(prompt=prompt, request_id=request_id, image_bytes=reference_image_bytes)
            logger.info(f"{provider_name} provider succeeded.")
            break
        except ImageProviderError as exc:
            last_error = exc
            logger.warning(
                "%s image provider failed for %s: %s",
                provider_name,
                request_id,
                exc,
            )
            continue

    if image_bytes is None:
        raise ImageProviderError("All image providers failed.") from last_error

    image = Image.open(BytesIO(image_bytes)).convert("RGB")
    image = image.resize((512, 512), Image.LANCZOS)
    buffer = BytesIO()
    image.save(buffer, format="PNG")
    encoded = base64.b64encode(buffer.getvalue()).decode("utf-8")

    return encoded
