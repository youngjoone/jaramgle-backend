
import base64
import logging
import os
import re
from io import BytesIO
from textwrap import dedent
from typing import Any, Dict, List, Optional, Tuple, Union
from urllib.parse import urlparse, unquote
from urllib.request import urlopen

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

def _generate_image_bytes(
    prompt: str,
    request_id: str,
    reference_images: Optional[List[bytes]] = None,
) -> Tuple[bytes, str]:
    providers: List[Tuple[str, ImageProvider]] = []
    if _prefer_google_image and _google_image_provider is not None:
        providers.append(("Google Gemini 2.5 Flash", _google_image_provider))
    providers.append(("OpenAI", _openai_image_provider))

    last_error: Optional[Exception] = None
    for provider_name, provider in providers:
        logger.info("Attempting image generation via %s for request_id %s", provider_name, request_id)
        try:
            image_bytes = provider.generate(
                prompt=prompt,
                request_id=request_id,
                image_bytes=reference_images or [],
            )
            logger.info("%s provider succeeded for request_id %s.", provider_name, request_id)
            return image_bytes, provider_name
        except ImageProviderError as exc:
            last_error = exc
            logger.warning("%s image provider failed for %s: %s", provider_name, request_id, exc)
            continue

    raise ImageProviderError("All image providers failed.") from last_error


def _encode_png_base64(image_bytes: bytes, size: Tuple[int, int] = (512, 512)) -> str:
    image = Image.open(BytesIO(image_bytes)).convert("RGB")
    if size:
        image.thumbnail(size, Image.LANCZOS)
        background = Image.new("RGB", size, (255, 255, 255))
        paste_x = (size[0] - image.width) // 2
        paste_y = (size[1] - image.height) // 2
        background.paste(image, (paste_x, paste_y))
        image = background

    buffer = BytesIO()
    image.save(buffer, format="PNG")
    return base64.b64encode(buffer.getvalue()).decode("utf-8")








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


    character_images: Optional[List[CharacterProfile]] = None,  # Kept for fallback


    include_metadata: bool = False,


) -> Union[str, Tuple[str, Dict[str, Any]]]:


    """


    Generates an image based on the provided text and character descriptions.


    It attempts to use the preferred Google provider first and falls back to OpenAI.


    Returns a base64 encoded PNG string by default, or a tuple of (base64, metadata)


    when include_metadata=True.


    """


    # 1. Define Style


    style_guide = art_style or _base_image_style


    if not art_style:


        logger.warning("No art_style supplied for %s; falling back to default style guide.", request_id)





    # 2. Define Characters


    character_descriptions = []


    if character_visuals:


        for visual in character_visuals:


            # Extract just the persona/personality part


            persona_match = re.search(r"Persona: ([^|]+)", visual.visual_description, re.IGNORECASE)


            if persona_match:


                persona = persona_match.group(1).strip()


                character_descriptions.append(f"- {visual.name}: {persona}")


            elif visual.name: # Fallback to name if persona not found


                character_descriptions.append(f"- {visual.name}")





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


        - Strictly maintain character appearance from all provided reference images.


        - Absolutely no speech bubbles, captions, or text of any kind.


    """).strip()





    # 5. Assemble the final prompt


    prompt = dedent(


        f"""


        Task: Create an image for a children's storybook page.


        Style: {style_guide}


        Characters: {character_section or "As described in the scene."}


        Scene: Illustrate the moment where {scene_summary}





        Rules:


        {rules}


        """


    ).strip()

    logger.info("Image generation prompt for %s: %s", request_id, prompt)

    # --- Reference Image Collection (FIXED: Collect all images) ---
    reference_images_bytes: List[bytes] = []
    reference_sources: List[str] = []
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
                            data = f.read()
                            reference_images_bytes.append(data)
                            reference_sources.append(visual.image_url)
                        logger.info(f"Using local image file as reference: {local_path}")
                    elif parsed.scheme in {"http", "https"}:
                        try:
                            with urlopen(visual.image_url) as response:
                                data = response.read()
                                reference_images_bytes.append(data)
                                reference_sources.append(visual.image_url)
                            logger.info("Using remote image URL as reference: %s", visual.image_url)
                        except Exception as fetch_err:  # pragma: no cover - network dependent
                            logger.warning("Failed to download reference image %s: %s", visual.image_url, fetch_err)
                    else:
                        logger.warning(
                            "Non-file image_url provided but not yet supported: %s",
                            visual.image_url,
                        )
                except FileNotFoundError:
                    logger.warning(f"Reference image file not found: {local_path}")
                except Exception as e:
                    logger.error(f"Error reading reference image file {visual.image_url}: {e}")

    image_bytes, provider_used = _generate_image_bytes(
        prompt=prompt,
        request_id=request_id,
        reference_images=reference_images_bytes,
    )
    encoded = _encode_png_base64(image_bytes, size=(512, 512))

    if include_metadata:
        metadata: Dict[str, Any] = {
            "provider": provider_used,
            "prompt": prompt,
            "referenceCount": len(reference_images_bytes),
            "referenceSources": reference_sources,
        }
        if character_visuals:
            metadata["characters"] = [
                {
                    "name": visual.name,
                    "slug": getattr(visual, "slug", None),
                    "imageUrl": visual.image_url,
                    "usedReferenceImage": bool(visual.image_url),
                }
                for visual in character_visuals
            ]
        return encoded, metadata

    return encoded


def generate_character_reference_image(
    character_name: str,
    request_id: str,
    description_prompt: Optional[str] = None,
    art_style: Optional[str] = None,
) -> Tuple[str, Dict[str, Any]]:
    """
    Generates a single character reference illustration, focusing on a clean,
    front-facing pose that can be reused across story pages.
    Returns the base64 image and metadata describing the generation.
    """
    style_guide = art_style or _base_image_style
    description = description_prompt or f"Child-friendly description of {character_name} with a distinctive outfit."

    prompt = dedent(
        f"""
        {style_guide}

        Create a high-quality reference illustration for the character "{character_name}".
        Visual description: {description}

        Rules:
        - Full-body or three-quarter view, neutral/heroic stance, clear lighting.
        - Solid or softly gradient background so the silhouette is easy to read.
        - No text, speech bubbles, or additional characters.
        - Showcase signature outfit colors, accessories, and key props.
        """
    ).strip()

    image_bytes, provider_used = _generate_image_bytes(prompt=prompt, request_id=request_id, reference_images=None)
    encoded = _encode_png_base64(image_bytes, size=(512, 512))

    metadata: Dict[str, Any] = {
        "provider": provider_used,
        "prompt": prompt,
        "characterName": character_name,
    }
    return encoded, metadata
