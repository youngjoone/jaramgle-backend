
import base64
import logging
import os
import re
import time
from io import BytesIO
from textwrap import dedent
from typing import Any, Dict, List, Optional, Tuple, Union
from urllib.parse import urlparse, unquote
from urllib.request import urlopen

from PIL import Image, ImageOps
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

_google_image_providers: List[Tuple[str, ImageProvider]] = []
_prefer_google_image = Config.USE_GEMINI_IMAGE
_story_output_size: Tuple[int, int] = (
    Config.STORY_IMAGE_RESPONSE_WIDTH,
    Config.STORY_IMAGE_RESPONSE_HEIGHT,
)
_character_reference_output_size: Tuple[int, int] = (
    Config.CHARACTER_REFERENCE_IMAGE_RESPONSE_SIZE,
    Config.CHARACTER_REFERENCE_IMAGE_RESPONSE_SIZE,
)


def _parse_dimensions(size: str) -> Optional[Tuple[int, int]]:
    try:
        width, height = size.lower().split("x", 1)
        return int(width.strip()), int(height.strip())
    except Exception:  # pragma: no cover - defensive parsing
        return None


def _build_gemini_provider(model_name: str, location: str) -> Optional[Tuple[str, ImageProvider]]:
    try:
        provider = Gemini25FlashImageProvider(
            config=Gemini25FlashProviderConfig(
                model=model_name,
                project_id=Config.GOOGLE_PROJECT_ID,
                location=location,
                candidate_count=1,
            ),
        )
        provider_name = f"Google Gemini Image ({model_name}, {location})"
        logger.info(
            "Google Gemini image provider initialised (model=%s, location=%s)",
            model_name,
            location,
        )
        return provider_name, provider
    except ImageProviderError as exc:
        logger.warning("Gemini provider init failed (model=%s, location=%s): %s", model_name, location, exc)
        return None


if _prefer_google_image:
    try:
        if not Config.GOOGLE_PROJECT_ID or not Config.GOOGLE_LOCATION:
            raise ImageProviderError(
                "GOOGLE_PROJECT_ID and GOOGLE_LOCATION must be configured for Gemini image generation."
            )

        location = Config.GOOGLE_LOCATION.strip().strip('"').strip("'")
        primary_model = Config.GEMINI_IMAGE_MODEL.strip()
        primary_provider = _build_gemini_provider(primary_model, location)
        if primary_provider:
            _google_image_providers.append(primary_provider)

        if Config.GEMINI_IMAGE_429_FALLBACK_ENABLED:
            fallback_model = (Config.GEMINI_IMAGE_FALLBACK_MODEL or "").strip()
            if fallback_model and fallback_model != primary_model:
                fallback_provider = _build_gemini_provider(fallback_model, location)
                if fallback_provider:
                    _google_image_providers.append(fallback_provider)
            elif fallback_model == primary_model:
                logger.info("Gemini fallback model equals primary model; model fallback is disabled.")
    except ImageProviderError as exc:
        _prefer_google_image = False
        logger.warning(
            "Google Gemini provider disabled, falling back to OpenAI: %s",
            exc,
        )
    finally:
        if not _google_image_providers:
            _prefer_google_image = False

if not _prefer_google_image:
    logger.info("Using OpenAI image provider (model=%s)", Config.OPENAI_IMAGE_MODEL)

def _is_resource_exhausted_error(exc: Exception) -> bool:
    text = str(exc).upper()
    return "RESOURCE_EXHAUSTED" in text or "429" in text

def _generate_image_bytes(
    prompt: str,
    request_id: str,
    reference_images: Optional[List[bytes]] = None,
) -> Tuple[bytes, str]:
    providers: List[Tuple[str, ImageProvider]] = []
    if _prefer_google_image and _google_image_providers:
        providers.extend(_google_image_providers)
        if Config.OPENAI_IMAGE_FALLBACK_ENABLED and Config.OPENAI_API_KEY:
            providers.append(("OpenAI", _openai_image_provider))
    elif Config.OPENAI_API_KEY:
        providers.append(("OpenAI", _openai_image_provider))
    elif not providers:
        raise ImageProviderError("No image provider is configured. Set Gemini credentials.")

    logger.info(
        "Image provider order for request %s: %s",
        request_id,
        ", ".join(name for name, _ in providers),
    )

    last_error: Optional[Exception] = None
    max_attempts = max(1, Config.IMAGE_GENERATION_MAX_ATTEMPTS)
    backoff_base = max(0.0, Config.IMAGE_GENERATION_BACKOFF_SECONDS)

    attempt = 1
    while attempt <= max_attempts:
        for index, (provider_name, provider) in enumerate(providers):
            logger.info(
                "Attempting image generation via %s for request_id %s (attempt %s/%s)",
                provider_name,
                request_id,
                attempt,
                max_attempts,
            )
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
                logger.warning(
                    "%s image provider failed for %s on attempt %s/%s: %s",
                    provider_name,
                    request_id,
                    attempt,
                    max_attempts,
                    exc,
                )

                # 3.1 -> 2.5 model fallback is only allowed when the primary Gemini model is throttled (429).
                if (
                    _prefer_google_image
                    and len(_google_image_providers) > 1
                    and index == 0
                ):
                    if _is_resource_exhausted_error(exc):
                        logger.warning(
                            "Primary Gemini model throttled for request %s; trying fallback model.",
                            request_id,
                        )
                        continue
                    break

        should_retry = (
            attempt < max_attempts
            and last_error is not None
            and _is_resource_exhausted_error(last_error)
        )
        if not should_retry:
            break

        sleep_seconds = backoff_base * attempt
        if sleep_seconds > 0:
            logger.warning(
                "Image provider throttled for request %s. Backing off %.1fs before retry.",
                request_id,
                sleep_seconds,
            )
            time.sleep(sleep_seconds)
        attempt += 1

    raise ImageProviderError("All image providers failed.") from last_error


def _encode_png_base64(
    image_bytes: bytes,
    size: Optional[Tuple[int, int]] = None,
    *,
    resize_mode: str = "cover",
) -> str:
    image = Image.open(BytesIO(image_bytes)).convert("RGB")
    target_size = size or _story_output_size

    if target_size and target_size[0] > 0 and target_size[1] > 0:
        if resize_mode == "contain":
            image.thumbnail(target_size, Image.LANCZOS)
            background = Image.new("RGB", target_size, (255, 255, 255))
            paste_x = (target_size[0] - image.width) // 2
            paste_y = (target_size[1] - image.height) // 2
            background.paste(image, (paste_x, paste_y))
            image = background
        else:
            # cover mode keeps one consistent UI frame with no letterboxing.
            image = ImageOps.fit(
                image,
                target_size,
                method=Image.LANCZOS,
                centering=(0.5, 0.5),
            )

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


def _normalize_literal_token(token: str) -> str:
    return token.strip().strip("\"'`“”‘’")


def _is_literal_token_candidate(token: str) -> bool:
    if not token or len(token) > 8:
        return False
    if not re.fullmatch(r"[A-Za-z0-9+\-*/=:#@&%!?.,]{1,8}", token):
        return False
    if token.isalpha() and len(token) > 1 and not token.isupper():
        return False
    return True


def _extract_literal_detail_tokens(raw_text: Optional[str], limit: int = 6) -> List[str]:
    if not raw_text:
        return []

    candidates: List[str] = []
    contextual_patterns = [
        r"(?:알파벳|영문자|영어\s*글자|글자|문자|대문자|소문자|숫자|번호)\s*[:：]?\s*[\"'“”‘’]?([A-Za-z0-9+\-*/=:#@&%!?.,]{1,8})[\"'“”‘’]?",
        r"(?:letter|alphabet|character|uppercase|lowercase|digit|number)\s*(?:is|:|=)?\s*[\"'“”‘’]?([A-Za-z0-9+\-*/=:#@&%!?.,]{1,8})[\"'“”‘’]?",
    ]
    for pattern in contextual_patterns:
        for match in re.finditer(pattern, raw_text, flags=re.IGNORECASE):
            token = _normalize_literal_token(match.group(1))
            if _is_literal_token_candidate(token):
                candidates.append(token)

    # Quoted short literals are often exact learning targets (e.g. "A", "7", "+").
    for match in re.finditer(r"[\"'“”‘’]([A-Za-z0-9+\-*/=:#@&%!?.,]{1,4})[\"'“”‘’]", raw_text):
        token = _normalize_literal_token(match.group(1))
        if _is_literal_token_candidate(token):
            candidates.append(token)

    deduped: List[str] = []
    seen = set()
    for token in candidates:
        if token in seen:
            continue
        seen.add(token)
        deduped.append(token)
        if len(deduped) >= limit:
            break
    return deduped


def _build_literal_detail_rules(raw_text: Optional[str]) -> str:
    tokens = _extract_literal_detail_tokens(raw_text)
    if not tokens:
        return ""

    token_list = ", ".join(f"'{token}'" for token in tokens)
    return dedent(
        f"""
        - Keep creative freedom for composition, mood, and background details.
        - If the scene requires visible letters, digits, or symbols, render these exact tokens only: {token_list}.
        - Never substitute lookalike characters (for example A/H, O/0, I/1/l, B/8).
        - If a token cannot be shown clearly, omit it instead of rendering the wrong token.
        """
    ).strip()


# --- Public API ---








def generate_image(
    text: str,
    request_id: str,
    art_style: Optional[str] = None,
    character_visuals: Optional[List[CharacterVisual]] = None,
    character_images: Optional[List[CharacterProfile]] = None,  # Kept for fallback
    include_metadata: bool = False,
    output_size: Optional[Tuple[int, int]] = None,
    resize_mode: str = "cover",
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

    literal_detail_rules = _build_literal_detail_rules(text)





    # 4. Define Rules


    rules = dedent("""


        - Show characters mid-action with expressive body language and clear emotions.


        - Vary the composition and background to make the setting feel alive.


        - Strictly maintain character appearance from all provided reference images.


        - No speech bubbles or captions. Avoid decorative text unless the scene explicitly requires a specific learning token.


        - For each character, copy the silhouette, facial features, colors, outfit, and props exactly from the reference image. Never mix traits between characters or invent new costumes.


        - Keep rendering style, line weight, and lighting consistent across all characters so the illustration looks like the same storybook page.
        - Preserve each character's facial features (eyes, pupils, mouth), accessories, and costume details exactly as shown in the reference images.
        - Do not add extra limbs or duplicate body parts; match the number and placement of arms, legs, hands, and props from the references.


    """).strip()

    if literal_detail_rules:
        rules = f"{rules}\n\n{literal_detail_rules}"





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
    resolved_output_size = output_size or _story_output_size
    encoded = _encode_png_base64(
        image_bytes,
        size=resolved_output_size,
        resize_mode=resize_mode,
    )

    if include_metadata:
        metadata: Dict[str, Any] = {
            "provider": provider_used,
            "prompt": prompt,
            "referenceCount": len(reference_images_bytes),
            "referenceSources": reference_sources,
            "outputWidth": resolved_output_size[0],
            "outputHeight": resolved_output_size[1],
            "resizeMode": resize_mode,
        }
        if _google_image_providers:
            metadata["googleProviders"] = [name for name, _ in _google_image_providers]
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
    reference_images: Optional[List[bytes]] = None,
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

    image_bytes, provider_used = _generate_image_bytes(
        prompt=prompt,
        request_id=request_id,
        reference_images=reference_images or None,
    )
    encoded = _encode_png_base64(
        image_bytes,
        size=_character_reference_output_size,
        resize_mode="contain",
    )

    metadata: Dict[str, Any] = {
        "provider": provider_used,
        "prompt": prompt,
        "characterName": character_name,
        "referenceCount": len(reference_images or []),
    }
    return encoded, metadata
