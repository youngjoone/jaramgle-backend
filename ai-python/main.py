from fastapi import FastAPI, Body, HTTPException, status, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel
import uuid
import time
import logging
from collections import defaultdict
from datetime import datetime, timedelta

from schemas import GenerateRequest, GenerateResponse # Import all necessary models
from service.openai_client import OpenAIClient # Import OpenAIClient
from config import Config # Import Config class

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

app = FastAPI()

# Initialize OpenAIClient
openai_client = OpenAIClient(api_key=Config.OPENAI_API_KEY)

# CORS settings
origins = [
    "http://localhost:5173",  # React frontend
    "http://localhost:8080",  # Spring Boot backend
]

app.add_middleware(
    CORSMiddleware,
    allow_origins=origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# --- Request ID Middleware ---
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

# --- Common Error Response Helper ---
def common_error_response(status_code: int, code: str, message: str, request: Request):
    return JSONResponse(
        status_code=status_code,
        content={
            "code": code,
            "message": message,
            "requestId": request.state.request_id,
            "timestamp": datetime.now().isoformat()
        }
    )

# --- Exception Handlers ---
@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    errors = exc.errors()
    messages = []
    for error in errors:
        loc = ".".join(map(str, error["loc"]))
        messages.append(f"{loc}: {error['msg']}")
    return common_error_response(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        code="VALIDATION_ERROR",
        message=f"Invalid input: {'; '.join(messages)}",
        request=request
    )

@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException):
    return common_error_response(
        status_code=exc.status_code,
        code=exc.detail.get("code", "HTTP_ERROR") if isinstance(exc.detail, dict) else "HTTP_ERROR",
        message=exc.detail.get("message", exc.detail) if isinstance(exc.detail, dict) else str(exc.detail),
        request=request
    )

@app.exception_handler(Exception)
async def generic_exception_handler(request: Request, exc: Exception):
    logger.error(f"Unhandled exception for Request ID: {request.state.request_id}", exc_info=True)
    return common_error_response(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        code="INTERNAL_SERVER_ERROR",
        message="An unexpected error occurred.",
        request=request
    )

# --- Rate Limiting Middleware ---
# In-memory token bucket for demonstration
# Limits: 3 req/sec, 60 req/min per IP
class TokenBucket:
    def __init__(self, capacity_per_sec: int, capacity_per_min: int):
        self.capacity_per_sec = capacity_per_sec
        self.capacity_per_min = capacity_per_min
        self.tokens_per_sec = capacity_per_sec
        self.tokens_per_min = capacity_per_min
        self.last_refill_sec = datetime.now()
        self.last_refill_min = datetime.now()

    def refill(self):
        now = datetime.now()
        time_passed_sec = (now - self.last_refill_sec).total_seconds()
        time_passed_min = (now - self.last_refill_min).total_seconds() / 60

        self.tokens_per_sec = min(self.capacity_per_sec, self.tokens_per_sec + time_passed_sec * self.capacity_per_sec)
        self.tokens_per_min = min(self.capacity_per_min, self.tokens_per_min + time_passed_min * self.capacity_per_min)

        self.last_refill_sec = now
        self.last_refill_min = now

    def consume(self, amount: int = 1) -> bool:
        self.refill()
        if self.tokens_per_sec >= amount and self.tokens_per_min >= amount:
            self.tokens_per_sec -= amount
            self.tokens_per_min -= amount
            return True
        return False

ip_buckets = defaultdict(lambda: TokenBucket(capacity_per_sec=3, capacity_per_min=60))

@app.middleware("http")
async def rate_limit_middleware(request: Request, call_next):
    if request.url.path == "/ai/health": # Exclude health check from rate limiting
        return await call_next(request)

    client_ip = request.client.host if request.client else "unknown"
    bucket = ip_buckets[client_ip]

    if not bucket.consume():
        return common_error_response(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            code="RATE_LIMITED",
            message="Too many requests. Please try again later.",
            request=request
        )
    return await call_next(request)


# --- API Endpoints ---

@app.get("/ai/health")
def health_check():
    return {"status": "ok"}

@app.post("/ai/generate", response_model=GenerateResponse)
def generate_poem(request: Request, gen_req: GenerateRequest = Body(...)):
    try:
        # Call OpenAIClient to generate text
        response = openai_client.generate_text(gen_req, request.state.request_id)
        return response
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": "INVALID_REQUEST", "message": str(e)}
        )
    except Exception as e:
        import traceback
        traceback.print_exc()
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": "GENERATION_ERROR", "message": f"시 생성 중 오류 발생: {e}"}
        )