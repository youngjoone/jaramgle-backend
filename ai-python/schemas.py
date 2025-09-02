from pydantic import BaseModel, Field
from typing import List, Dict, Optional, Any, Literal # Import Literal

# --- Pydantic Models for /ai/generate ---

# New GenerateRequest
class GenerateRequest(BaseModel):
    age_range: str
    topics: List[str]
    objectives: List[str]
    min_pages: int
    language: Literal["KO", "EN"]
    title: Optional[str] = None

# New StoryOutput
class StoryPage(BaseModel):
    page: int
    text: str

class QA(BaseModel):
    q: str
    a: str

class StoryOutput(BaseModel):
    title: str
    pages: List[StoryPage]
    quiz: Optional[List[QA]] = None

class Moderation(BaseModel):
    safe: bool = True
    flags: List[str] = Field(default_factory=list)

# New GenerateResponse
class GenerateResponse(BaseModel):
    story: StoryOutput
    raw_json: str
    moderation: Moderation = Field(default_factory=Moderation)

# Old models (can be removed after main.py is updated)
# class Traits(BaseModel):
#     A: Optional[float] = 0.0
#     B: Optional[float] = 0.0
#     C: Optional[float] = 0.0

# class Profile(BaseModel):
#     traits: Optional[Traits] = Field(default_factory=Traits)

# class Mood(BaseModel):
#     tags: Optional[List[str]] = Field(default_factory=list)
#     intensity: Optional[int] = 50

# class GenerateRequest(BaseModel): # Old GenerateRequest
#     profile: Optional[Profile] = Field(default_factory=Profile)
#     mood: Optional[Mood] = Field(default_factory=Mood)
#     want: Optional[List[str]] = Field(default_factory=list)

# class GenerateResponse(BaseModel): # Old GenerateResponse
#     poem: Optional[str] = None
#     img_prompt: Optional[str] = None
#     moderation: Moderation = Field(default_factory=Moderation)

# Old PoemResponse (can be removed after main.py is updated)
# class PoemResponse(BaseModel):
#     poem: str