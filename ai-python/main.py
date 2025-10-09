
# 1. Standard library imports
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
    SynthesizeFromPlanRequest,
)
from service.text_service import generate_story_with_plan
from service.image_service import generate_image
from service.audio_service import synthesize_story_from_plan, create_tts

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
        response = generate_story_with_plan(gen_req, request.state.request_id)
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
        b64_json = generate_image(
            text=img_req.text, 
            request_id=request.state.request_id, 
            character_images=img_req.characters,
            art_style=img_req.art_style, 
            character_visuals=img_req.character_visuals
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
def generate_audio_endpoint(request: Request, audio_req: SynthesizeFromPlanRequest = Body(...)):
    audio_dir = "/Users/kyj/testaudiodir"
    try:
        os.makedirs(audio_dir, exist_ok=True)
        audio_bytes = synthesize_story_from_plan(
            reading_plan=audio_req.reading_plan,
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
