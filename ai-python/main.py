
# 1. Standard library imports
import asyncio
import base64
import logging
import os
import time
import traceback
import uuid
from collections import defaultdict
from datetime import datetime, timedelta

# 2. FastAPI imports
from fastapi import FastAPI, Body, HTTPException, status, Request, Response
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
    GenerateAudioFromStoryRequest,
    GeneratePageAssetsRequest, # Added
)
from service.text_service import generate_story
from service.image_service import generate_image
from service.audio_service import synthesize_story_from_plan, create_tts, plan_reading_segments, plan_and_synthesize_audio # Added

# --- App Initialization and Logging ---

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

app = FastAPI()

logger.info(f"Text generation will be handled by text_service with provider: {Config.LLM_PROVIDER}")

# --- Middleware ---

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
    IMAGE_DIR = "/Users/kyj/testimagedir"
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
        
        image_data = base64.b64decode(b64_json)
        filename = f"{uuid.uuid4()}.png"
        file_path = os.path.join(IMAGE_DIR, filename)
        
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

@app.post("/ai/generate-audio")
def generate_audio_endpoint(request: Request, audio_req: GenerateAudioFromStoryRequest = Body(...)):
    audio_dir = "/Users/kyj/testaudiodir"
    try:
        os.makedirs(audio_dir, exist_ok=True)

        # 1. Generate reading plan from story text
        reading_plan = plan_reading_segments(
            story_text=audio_req.story_text,
            characters=audio_req.characters,
            request_id=request.state.request_id
        )

        # 2. Synthesize audio from the generated plan
        audio_bytes = synthesize_story_from_plan(
            reading_plan=reading_plan,
            characters=audio_req.characters,
            language=audio_req.language,
            request_id=request.state.request_id
        )

        filename = f"{uuid.uuid4()}.wav"
        file_path = os.path.join(audio_dir, filename)

        with open(file_path, "wb") as f:
            f.write(audio_bytes)

        logger.info("Audio file saved to %s", file_path)
        return Response(content=filename, media_type="text/plain")

    except Exception as e:
        logger.error(f"Audio generation failed for Request ID: {request.state.request_id}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": "AUDIO_GENERATION_ERROR", "message": str(e)},
        )

@app.post("/ai/generate-page-assets")
async def generate_page_assets_endpoint(request: Request, req: GeneratePageAssetsRequest = Body(...)):
    audio_dir = "/Users/kyj/testaudiodir"
    image_dir = "/Users/kyj/testimagedir"
    try:
        os.makedirs(audio_dir, exist_ok=True)
        os.makedirs(image_dir, exist_ok=True)

        # Run image and audio generation in parallel
        image_task = asyncio.create_task(
            asyncio.to_thread(
                generate_image,
                text=req.text,
                request_id=request.state.request_id,
                art_style=req.art_style,
                character_visuals=req.character_visuals,
            )
        )
        audio_task = asyncio.create_task(
            asyncio.to_thread(
                plan_and_synthesize_audio,
                story_text=req.text,
                characters=req.character_visuals, # Note: CharacterVisual is used here, not CharacterProfile
                language="KO", # Assuming default language for now, can be passed in request if needed
                request_id=request.state.request_id,
            )
        )

        image_b64_json, audio_bytes = await asyncio.gather(image_task, audio_task)

        # Save image
        image_data = base64.b64decode(image_b64_json)
        image_filename = f"{uuid.uuid4()}.png"
        image_file_path = os.path.join(image_dir, image_filename)
        with open(image_file_path, "wb") as f:
            f.write(image_data)
        logger.info(f"Image saved to {image_file_path}")

        # Save audio
        audio_filename = f"{uuid.uuid4()}.wav"
        audio_file_path = os.path.join(audio_dir, audio_filename)
        with open(audio_file_path, "wb") as f:
            f.write(audio_bytes)
        logger.info(f"Audio file saved to {audio_file_path}")

        return JSONResponse(content={
            "imageUrl": f"/api/image/{image_filename}", # Assuming a route to serve images
            "audioUrl": f"/api/audio/{audio_filename}"  # Assuming a route to serve audio
        })

    except Exception as e:
        logger.error(f"Page assets generation failed for Request ID: {request.state.request_id}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": "PAGE_ASSETS_GENERATION_ERROR", "message": str(e)},
        )

@app.post("/ai/generate-tts")
def generate_tts_endpoint(request: Request, text: str = Body(..., media_type="text/plain")):
    audio_dir = "/Users/kyj/testaudiodir"
    try:
        os.makedirs(audio_dir, exist_ok=True)
        audio_data = create_tts(text)
        
        filename = f"{uuid.uuid4()}.wav"
        file_path = os.path.join(audio_dir, filename)
        
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
