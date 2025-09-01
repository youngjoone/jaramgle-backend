from pydantic import BaseModel, Field
from typing import List, Dict, Optional, Any

# --- Pydantic Models for /ai/generate ---

class Traits(BaseModel):
    A: Optional[float] = 0.0
    B: Optional[float] = 0.0
    C: Optional[float] = 0.0

class Profile(BaseModel):
    traits: Optional[Traits] = Field(default_factory=Traits)

class Mood(BaseModel):
    tags: Optional[List[str]] = Field(default_factory=list)
    intensity: Optional[int] = 50

class GenerateRequest(BaseModel):
    profile: Optional[Profile] = Field(default_factory=Profile)
    mood: Optional[Mood] = Field(default_factory=Mood)
    want: Optional[List[str]] = Field(default_factory=list)

class Moderation(BaseModel):
    safe: bool = True
    flags: List[str] = Field(default_factory=list)

class GenerateResponse(BaseModel):
    poem: Optional[str] = None
    img_prompt: Optional[str] = None
    moderation: Moderation = Field(default_factory=Moderation)

# Old PoemResponse (can be removed after main.py is updated)
class PoemResponse(BaseModel):
    poem: str
