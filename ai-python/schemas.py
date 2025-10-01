import re
from typing import List, Optional, Union, Literal
from pydantic import BaseModel, Field, ConfigDict, field_validator

def to_camel(string: str) -> str:
    components = string.split('_')
    # We capitalize the first letter of each component except the first one
    # with the 'title' method and join them together.
    return components[0] + ''.join(x.title() for x in components[1:])

# -------- GenerateRequest --------
class CharacterProfile(BaseModel):
    id: int
    slug: str
    name: str
    persona: Optional[str] = None
    catchphrase: Optional[str] = None
    prompt_keywords: Optional[str] = Field(default=None, alias="promptKeywords")
    image_path: Optional[str] = Field(default=None, alias="imagePath")

    model_config = ConfigDict(populate_by_name=True, extra="ignore")


class GenerateRequest(BaseModel):
    age_range: Union[int, str]
    topics: List[str]
    objectives: List[str]
    min_pages: int = Field(ge=1, le=20)
    language: Union[Literal["KO", "EN"], str]
    title: Optional[str] = None
    characters: List[CharacterProfile] = Field(default_factory=list)

    model_config = ConfigDict(extra="ignore")

    @field_validator("age_range", mode="before")
    def _age_to_str(cls, v):
        return str(v)

    @field_validator("topics", "objectives", mode="before")
    def _to_list(cls, v):
        if isinstance(v, str):
            return [s.strip() for s in v.split(",") if s.strip()]
        return v

    @field_validator("language", mode="before")
    def _norm_lang(cls, v):
        if isinstance(v, str):
            u = v.strip().upper()
            if u in {"KO", "KOREAN", "KR", "KO-KR", "KO_KR"}:
                return "KO"
            if u in {"EN", "ENG", "EN-US", "EN_GB", "EN-GB"}:
                return "EN"
        return v

# -------- Story / Quiz / Concept (NEW) --------
class StoryPage(BaseModel):
    page_no: int = Field(..., alias="page")
    text: str
    id: Optional[int] = None

    model_config = ConfigDict(populate_by_name=True, extra="ignore")

class QA(BaseModel):
    question: str = Field(..., alias="q")
    options: List[str]
    answer: int = Field(..., alias="a")

    model_config = ConfigDict(populate_by_name=True, extra="ignore")

class CharacterSheet(BaseModel):
    name: str
    visual_description: str = Field(..., description="Detailed visual description for the image generation AI.")
    voice_profile: str = Field(..., description="Voice tone and emotion guide for the TTS AI.")

class CreativeConcept(BaseModel):
    art_style: str = Field(..., description="A unified art style guide for all illustrations.")
    mood_and_tone: str = Field(..., description="The overall mood and tone for the story, art, and audio.")
    character_sheets: List[CharacterSheet] = Field(default_factory=list)

class StoryOutput(BaseModel):
    title: str
    pages: List[StoryPage]
    quiz: List[QA] = Field(default_factory=list)

    model_config = ConfigDict(extra="ignore")

# -------- Moderation / Response --------
class Moderation(BaseModel):
    safe: bool = True
    flags: List[str] = Field(default_factory=list)

class GenerateResponse(BaseModel):
    story: StoryOutput
    creative_concept: Optional[CreativeConcept] = None # ADDED
    raw_json: str
    moderation: Moderation = Field(default_factory=Moderation)

# -------- Image Generation --------
class CharacterVisual(BaseModel):
    name: str
    visual_description: str

    model_config = ConfigDict(populate_by_name=True, alias_generator=to_camel) # ADDED

class GenerateImageRequest(BaseModel):
    text: str
    characters: List[CharacterProfile] = Field(default_factory=list)
    art_style: Optional[str] = None
    character_visuals: List[CharacterVisual] = Field(default_factory=list)

    model_config = ConfigDict(populate_by_name=True, alias_generator=to_camel) # ADDED

class GenerateImageResponse(BaseModel):
    file_path: str

# -------- Audio Generation --------
class AudioPageInput(BaseModel):
    page_no: int = Field(..., alias="pageNo")
    text: str

    model_config = ConfigDict(populate_by_name=True, extra="ignore")


class GenerateAudioRequest(BaseModel):
    title: Optional[str] = None
    language: Optional[str] = None
    pages: List[AudioPageInput]
    characters: List[CharacterProfile] = Field(default_factory=list)

    model_config = ConfigDict(populate_by_name=True, extra="ignore")

    @field_validator("language", mode="before")
    def _normalize_language(cls, value: Optional[str]):
        if value is None:
            return value
        normalized = str(value).strip().upper()
        if normalized in {"KO", "KOREAN", "KO-KR", "KO_KR"}:
            return "KO"
        if normalized in {"EN", "ENGLISH", "EN-US", "EN_US", "EN-GB", "EN_GB"}:
            return "EN"
        return normalized