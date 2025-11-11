
# 1. Standard library imports
import asyncio
import base64
import hashlib
import logging
import os
import re
import time
import traceback
import uuid
from collections import defaultdict
from datetime import datetime, timedelta
from pathlib import Path
from textwrap import dedent

# 2. FastAPI imports
from typing import Optional

from fastapi import FastAPI, Body, HTTPException, status, Request, Response, Query
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

# 3. Local application imports
from config import Config
from schemas import (
    GenerateRequest,
    GenerateResponse,
    GenerateImageRequest,
    GenerateImageResponse,
    CreateCharacterReferenceImageRequest,
    CharacterReferenceImageResponse,
    GeneratePageAssetsRequest, # Added
    GenerateCoverImageRequest,
    GenerateParagraphAudioRequest,
    GenerateParagraphAudioResponse,
)
from service.text_service import generate_story
from service.image_service import generate_image, generate_character_reference_image
from service.audio_service import (
    create_tts,
    create_gemini_tts,
    get_gemini_tts_extension,
    generate_paragraph_audio,
) # Added

# --- App Initialization and Logging ---

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

app = FastAPI()

logger.info(f"Text generation will be handled by text_service with provider: {Config.LLM_PROVIDER}")

# --- Middleware ---

_AUDIO_BASE_DIR = "/Users/kyj/testaudiodir"
_IMAGE_BASE_DIR = "/Users/kyj/testimagedir"

def _slugify_component(value: Optional[str], fallback: str = "segment") -> str:
    candidate = str(value).strip().lower() if value is not None else ""
    candidate = re.sub(r"[^a-z0-9]+", "-", candidate).strip("-")
    return candidate or fallback

def _hash_text_payload(text: str) -> str:
    return hashlib.sha1(text.encode("utf-8")).hexdigest()[:10]

# CORS settings
origins = [
    "http://localhost:5173",
    "http://localhost:8080",
]
app.add_middleware(
    CORSMiddleware,
    allow_origins=origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.middleware("http")
async def add_request_id_middleware(request: Request, call_next):
    request_id = str(uuid.uuid4())
    request.state.request_id = request_id
    start_time = time.time()

    response = await call_next(request)

    process_time = time.time() - start_time
    response.headers["X-Request-Id"] = request_id
    logger.info(f"Request ID: {request_id} - Method: {request.method} - Path: {request.url.path} - Status: {response.status_code} - Process Time: {process_time:.4f}s")
    return response

# --- Exception Handlers ---

@app.exception_handler(Exception)
async def generic_exception_handler(request: Request, exc: Exception):
    logger.error(f"Unhandled exception for Request ID: {request.state.request_id}", exc_info=True)
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content={
            "code": "INTERNAL_SERVER_ERROR",
            "message": f"An unexpected error occurred: {str(exc)}",
            "requestId": request.state.request_id,
            "timestamp": datetime.now().isoformat()
        }
    )

# --- API Endpoints ---

@app.get("/ai/health")
def health_check():
    return {"status": "ok"}

@app.post("/ai/generate", response_model=GenerateResponse)
def generate_story_endpoint(request: Request, gen_req: GenerateRequest = Body(...)):
    try:
        response = generate_story(gen_req, request.state.request_id)
        return JSONResponse(content=response.model_dump())
    except Exception as e:
        logger.error(f"Story generation failed for Request ID: {request.state.request_id}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": "GENERATION_ERROR", "message": f"스토리 생성 중 오류 발생: {e}"}
        )

@app.post("/ai/generate-image", response_model=GenerateImageResponse)
def generate_image_endpoint(request: Request, img_req: GenerateImageRequest = Body(...)):
    try:
        combined_visuals = list(img_req.character_visuals)
        if img_req.characters:
            existing_names = {visual.name for visual in combined_visuals if visual.name}
            for visual in img_req.characters:
                if visual.name and visual.name in existing_names:
                    continue
                combined_visuals.append(visual)
                if visual.name:
                    existing_names.add(visual.name)

        b64_json = generate_image(
            text=img_req.text, 
            request_id=request.state.request_id, 
            art_style=img_req.art_style, 
            character_visuals=combined_visuals
        )
        
        os.makedirs(_IMAGE_BASE_DIR, exist_ok=True)

        image_data = base64.b64decode(b64_json)
        filename = f"{uuid.uuid4()}.png"
        file_path = os.path.join(_IMAGE_BASE_DIR, filename)
        
        with open(file_path, "wb") as f:
            f.write(image_data)
            
        logger.info(f"Image saved to {file_path}")
        return GenerateImageResponse(file_path=filename)

    except Exception as e:
        logger.error(f"Image generation failed for Request ID: {request.state.request_id}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": "IMAGE_GENERATION_ERROR", "message": str(e)}
        )

@app.post("/ai/create-character-reference-image", response_model=CharacterReferenceImageResponse)
def create_character_reference_image_endpoint(
    request: Request,
    ref_req: CreateCharacterReferenceImageRequest = Body(...)
):
    try:
        os.makedirs(Config.CHARACTER_IMAGE_DIR, exist_ok=True)

        slug_source = ref_req.slug or ref_req.character_name
        slug_candidate = re.sub(r"[^a-z0-9]+", "-", slug_source.lower()).strip("-") if slug_source else "character"
        slug_candidate = slug_candidate or "character"
        filename = f"{slug_candidate}-{uuid.uuid4().hex}.png"

        image_b64, metadata = generate_character_reference_image(
            character_name=ref_req.character_name,
            request_id=request.state.request_id,
            description_prompt=ref_req.description_prompt,
            art_style=ref_req.art_style,
        )

        image_data = base64.b64decode(image_b64)
        file_path = os.path.join(Config.CHARACTER_IMAGE_DIR, filename)
        with open(file_path, "wb") as f:
            f.write(image_data)

        file_uri = Path(file_path).resolve().as_uri()
        logger.info("Reference image saved to %s for character %s", file_path, ref_req.character_name)

        return CharacterReferenceImageResponse(
            image_url=file_uri,
            modeling_status="COMPLETED",
            metadata=metadata,
        )
    except Exception as e:
        logger.error(
            "Reference image generation failed for Request ID: %s",
            request.state.request_id,
            exc_info=True,
        )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": "REFERENCE_IMAGE_ERROR", "message": str(e)},
        )




@app.post("/ai/generate-page-audio", response_model=GenerateParagraphAudioResponse)
def generate_page_audio_endpoint(
    request: Request,
    audio_req: GenerateParagraphAudioRequest = Body(...),
):
    try:
        os.makedirs(_AUDIO_BASE_DIR, exist_ok=True)

        story_component = _slugify_component(str(audio_req.story_id), "story")
        page_component = _slugify_component(str(audio_req.page_id), "page")
        base_dir = os.path.join(_AUDIO_BASE_DIR, "stories", story_component, page_component)
        os.makedirs(base_dir, exist_ok=True)

        name_parts = []
        if audio_req.paragraph_id is not None:
            name_parts.append(_slugify_component(str(audio_req.paragraph_id), "paragraph"))
        if audio_req.speaker_slug:
            name_parts.append(_slugify_component(audio_req.speaker_slug, "speaker"))

        name_parts.append(_hash_text_payload(audio_req.text))
        base_name = "-".join(part for part in name_parts if part) or f"segment-{_hash_text_payload(audio_req.text)}"

        preferred_ext = get_gemini_tts_extension()
        existing_path = None
        search_order = [preferred_ext, "mp3", "wav", "ogg"]
        for ext in search_order:
            candidate = os.path.join(base_dir, f"{base_name}.{ext}")
            if os.path.exists(candidate):
                existing_path = candidate
                preferred_ext = ext
                break

        if existing_path and not audio_req.force_regenerate:
            rel_path = os.path.relpath(existing_path, _AUDIO_BASE_DIR).replace(os.sep, "/")
            url = f"/api/audio/{rel_path}"
            logger.info(
                "Reusing paragraph audio at %s for request %s",
                existing_path,
                request.state.request_id,
            )
            return GenerateParagraphAudioResponse(
                file_path=rel_path,
                url=url,
                provider="Gemini TTS",
                already_existed=True,
            )

        audio_bytes, extension = generate_paragraph_audio(
            audio_req.text,
            speaker_slug=audio_req.speaker_slug,
            emotion=audio_req.emotion,
            style_hint=audio_req.style_hint,
            request_id=request.state.request_id,
        )

        filename = f"{base_name}.{extension}"
        full_path = os.path.join(base_dir, filename)
        with open(full_path, "wb") as f:
            f.write(audio_bytes)

        rel_path = os.path.relpath(full_path, _AUDIO_BASE_DIR).replace(os.sep, "/")
        url = f"/api/audio/{rel_path}"
        logger.info("Paragraph audio saved to %s", full_path)
        return GenerateParagraphAudioResponse(
            file_path=rel_path,
            url=url,
            provider="Gemini TTS",
            already_existed=False,
        )

    except Exception as e:
        logger.error(
            "Paragraph audio generation failed for Request ID: %s",
            request.state.request_id,
            exc_info=True,
        )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": "PARAGRAPH_AUDIO_ERROR", "message": str(e)},
        )

@app.post("/ai/generate-page-assets")
async def generate_page_assets_endpoint(request: Request, req: GeneratePageAssetsRequest = Body(...)):
    try:
        os.makedirs(_AUDIO_BASE_DIR, exist_ok=True)
        os.makedirs(_IMAGE_BASE_DIR, exist_ok=True)

        image_result = await asyncio.to_thread(
            generate_image,
            text=req.text,
            request_id=request.state.request_id,
            art_style=req.art_style,
            character_visuals=req.character_visuals,
            include_metadata=True,
        )

        if isinstance(image_result, tuple) and len(image_result) == 2:
            image_b64_json, image_metadata = image_result
        else:
            image_b64_json = image_result
            image_metadata = {}

        # Save image
        image_data = base64.b64decode(image_b64_json)
        image_filename = f"{uuid.uuid4()}.png"
        image_file_path = os.path.join(_IMAGE_BASE_DIR, image_filename)
        with open(image_file_path, "wb") as f:
            f.write(image_data)
        logger.info(f"Image saved to {image_file_path}")

        metadata_characters = {}
        if isinstance(image_metadata, dict):
            metadata_characters = {
                (entry.get("slug") or entry.get("name") or "").lower(): entry
                for entry in image_metadata.get("characters", [])
                if isinstance(entry, dict)
            }

        character_processing = []
        for visual in req.character_visuals:
            key = (visual.slug or visual.name or "").lower()
            metadata_entry = metadata_characters.get(key) if key else None
            used_reference = None
            if metadata_entry is not None:
                used_reference = bool(metadata_entry.get("usedReferenceImage"))
            else:
                used_reference = bool(visual.image_url)
            modeling_status = visual.modeling_status
            if used_reference and modeling_status:
                if modeling_status.upper() in {"PENDING", "FAILED"}:
                    modeling_status = "COMPLETED"
            elif used_reference and not modeling_status:
                modeling_status = "COMPLETED"
            character_processing.append({
                "name": visual.name,
                "slug": visual.slug,
                "modelingStatus": modeling_status,
                "imageUrl": visual.image_url,
                "usedReferenceImage": used_reference,
            })

        response_payload = {
            "imageUrl": f"/api/image/{image_filename}",  # Assuming a route to serve images
            "imageMetadata": image_metadata,
            "characterProcessingResults": character_processing,
        }

        return JSONResponse(content=response_payload)

    except Exception as e:
        logger.error(f"Page assets generation failed for Request ID: {request.state.request_id}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": "PAGE_ASSETS_GENERATION_ERROR", "message": str(e)},
        )

@app.post("/ai/generate-cover-image")
async def generate_cover_image_endpoint(request: Request, req: GenerateCoverImageRequest = Body(...)):
    try:
        os.makedirs(_IMAGE_BASE_DIR, exist_ok=True)

        character_names = ", ".join([visual.name for visual in req.character_visuals if visual.name]) or "the main characters"
        summary_line = req.summary or "A heartwarming children's adventure."
        tagline = req.tagline or ""
        style_line = req.art_style or "soft watercolor storybook illustration with warm pastel lighting"

        cover_prompt = dedent(
            f"""
            Design a single, polished children's storybook cover illustration.

            Title: "{req.title}"
            Story summary: {summary_line}
            Tagline / moral cue: {tagline or "Highlight friendship, curiosity, and joy."}
            Featured characters: {character_names}

            Requirements:
            - Use {style_line} consistently across the entire cover.
            - Focus on the main characters interacting in a dynamic yet readable composition.
            - Include subtle environmental hints drawn from the summary (beach, sea, magical light if mentioned).
            - Leave breathing room for typography, but do NOT draw any text, title, or logos.
            - Keep proportions cute and cohesive so this cover matches the rest of the story's illustrations.
            """
        ).strip()

        image_result = await asyncio.to_thread(
            generate_image,
            text=cover_prompt,
            request_id=request.state.request_id,
            art_style=req.art_style,
            character_visuals=req.character_visuals,
            include_metadata=True,
        )

        if isinstance(image_result, tuple) and len(image_result) == 2:
            image_b64_json, image_metadata = image_result
        else:
            image_b64_json = image_result
            image_metadata = {}

        image_data = base64.b64decode(image_b64_json)
        filename = f"cover-{uuid.uuid4()}.png"
        file_path = os.path.join(_IMAGE_BASE_DIR, filename)
        with open(file_path, "wb") as f:
            f.write(image_data)
        logger.info("Cover image saved to %s", file_path)

        return JSONResponse(
            content={
                "imageUrl": f"/api/image/{filename}",
                "imageMetadata": image_metadata,
            }
        )

    except Exception as e:
        logger.error("Cover image generation failed for Request ID: %s", request.state.request_id, exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": "COVER_IMAGE_ERROR", "message": str(e)},
        )

@app.post("/ai/generate-tts")
def generate_tts_endpoint(
    request: Request,
    text: str = Body(..., media_type="text/plain"),
    provider: Optional[str] = Query(None),
):
    try:
        os.makedirs(_AUDIO_BASE_DIR, exist_ok=True)
        if provider:
            provider_key = provider.strip().lower()
        else:
            provider_key = "gemini" if Config.USE_GEMINI_TTS else "openai"
        if provider_key == "gemini":
            audio_data = create_gemini_tts(text)
            extension = get_gemini_tts_extension()
        elif provider_key == "openai":
            audio_data = create_tts(text)
            extension = "wav"
        else:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail={
                    "code": "TTS_PROVIDER_UNSUPPORTED",
                    "message": f"Unsupported TTS provider '{provider_key}'",
                },
            )

        filename = f"{uuid.uuid4()}.{extension}"
        file_path = os.path.join(_AUDIO_BASE_DIR, filename)
        
        with open(file_path, "wb") as f:
            f.write(audio_data)
            
        logger.info("TTS audio file saved to %s", file_path)
        return Response(content=filename, media_type="text/plain")

    except Exception as e:
        logger.error(f"TTS generation failed for Request ID: {request.state.request_id}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": "TTS_GENERATION_ERROR", "message": str(e)}
        )
