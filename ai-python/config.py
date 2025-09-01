import os
from dotenv import load_dotenv

load_dotenv()

class Config:
    OPENAI_API_KEY: str = os.getenv("OPENAI_API_KEY", "")
    OPENAI_MODEL: str = os.getenv("OPENAI_MODEL", "gpt-5-mini") # Default to gpt-5-mini
    OPENAI_MAX_OUTPUT_TOKENS: int = int(os.getenv("OPENAI_MAX_OUTPUT_TOKENS", "300"))
    OPENAI_TEMPERATURE: float = float(os.getenv("OPENAI_TEMPERATURE", "0.7"))

    # Add other models for reference
    GPT_4O: str = "gpt-4o"
    GPT_5_MINI: str = "gpt-5-mini" # Assuming this model exists for the purpose of this exercise
