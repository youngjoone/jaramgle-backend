import os
from dotenv import load_dotenv

load_dotenv()


def _env_flag(name: str, *, default: bool = False) -> bool:
    """Return a boolean from environment variables, with an explicit default."""
    value = os.getenv(name)
    if value is None:
        return default
    return value.lower() in {"1", "true", "yes", "on"}

class Config:
    OPENAI_API_KEY: str = os.getenv("OPENAI_API_KEY", "")
    OPENAI_MODEL: str = os.getenv("OPENAI_MODEL", "gpt-5-mini")  # Default to gpt-5-mini
    OPENAI_MAX_OUTPUT_TOKENS: int = int(os.getenv("OPENAI_MAX_OUTPUT_TOKENS", "300"))
    OPENAI_TEMPERATURE: float = float(os.getenv("OPENAI_TEMPERATURE", "0.7"))
    CHARACTER_IMAGE_DIR: str = os.getenv("CHARACTER_IMAGE_DIR", "/Users/kyj/testchardir")
    OPENAI_IMAGE_MODEL: str = os.getenv("OPENAI_IMAGE_MODEL", "gpt-image-1")
    OPENAI_IMAGE_SIZE: str = "1024x1024"
    OPENAI_IMAGE_QUALITY: str = os.getenv("OPENAI_IMAGE_QUALITY", "medium")

    GEMINI_API_KEY: str = os.getenv("GEMINI_API_KEY", "")
    LLM_PROVIDER: str = os.getenv("LLM_PROVIDER", "gemini")  # 'openai' or 'gemini'
    GEMINI_IMAGE_MODEL: str = os.getenv("GEMINI_IMAGE_MODEL", "gemini-2.5-flash-image")
    DEFAULT_USE_GEMINI_IMAGE: bool = True  # Toggle here when you do not want to use environment variables
    USE_GEMINI_IMAGE: bool = _env_flag("USE_GEMINI_IMAGE", default=DEFAULT_USE_GEMINI_IMAGE)
    IMAGE_GENERATION_MAX_CONCURRENCY: int = int(os.getenv("IMAGE_GENERATION_MAX_CONCURRENCY", "3"))
    IMAGE_GENERATION_QUEUE_TIMEOUT_SECONDS: float = float(os.getenv("IMAGE_GENERATION_QUEUE_TIMEOUT_SECONDS", "5"))
    IMAGE_GENERATION_MAX_ATTEMPTS: int = int(os.getenv("IMAGE_GENERATION_MAX_ATTEMPTS", "3"))
    IMAGE_GENERATION_BACKOFF_SECONDS: float = float(os.getenv("IMAGE_GENERATION_BACKOFF_SECONDS", "2"))

    # Google Cloud project settings (for Vertex AI models like Imagen)
    GOOGLE_PROJECT_ID: str = os.getenv("GOOGLE_PROJECT_ID", "")
    GOOGLE_LOCATION: str = os.getenv("GOOGLE_LOCATION", "us-central1")

    # Azure Speech configuration (optional)
    AZURE_SPEECH_KEY: str = os.getenv("AZURE_SPEECH_KEY", "")
    AZURE_SPEECH_REGION: str = os.getenv("AZURE_SPEECH_REGION", "")
    USE_AZURE_TTS: bool = os.getenv("USE_AZURE_TTS", "false").lower() in {"1", "true", "yes", "on"}
    AZURE_TTS_OUTPUT_FORMAT: str = os.getenv("AZURE_TTS_OUTPUT_FORMAT", "riff-24khz-16bit-mono-pcm")
    USE_GEMINI_TTS: bool = _env_flag("USE_GEMINI_TTS", default=True)
    GEMINI_TTS_MODEL: str = os.getenv("GEMINI_TTS_MODEL", "gemini-2.5-flash-preview-tts")
    GEMINI_TTS_VOICE: str = os.getenv("GEMINI_TTS_VOICE", "Charon")
    GEMINI_TTS_LANGUAGE: str = os.getenv("GEMINI_TTS_LANGUAGE", "en-US")
    GEMINI_TTS_AUDIO_ENCODING: str = os.getenv("GEMINI_TTS_AUDIO_ENCODING", "LINEAR16")
    GEMINI_TTS_PROMPT_TEMPLATE: str = os.getenv(
        "GEMINI_TTS_PROMPT_TEMPLATE",
        "Narrate the following passage for a children's story with a warm and engaging tone.",
    )
    READING_PLAN_MODEL: str = os.getenv("READING_PLAN_MODEL", "gpt-4o-mini")

    # Add other models for reference
    GPT_4O: str = "gpt-4o"
    GPT_5_MINI: str = "gpt-5-mini" # Assuming this model exists for the purpose of this exercise
