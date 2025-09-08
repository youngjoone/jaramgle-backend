# schemas.py
from typing import List, Optional, Union, Literal
from pydantic import BaseModel, Field, ConfigDict, field_validator

# -------- GenerateRequest --------
class GenerateRequest(BaseModel):
    # 백엔드가 int로 줄 수도, "5" 문자열로 줄 수도 있으니 둘 다 허용
    age_range: Union[int, str]
    # 클라이언트가 "a, b, c" 같은 문자열로 줄 때도 리스트로 변환되게 처리
    topics: List[str]
    objectives: List[str]
    min_pages: int = Field(ge=1, le=20)
    # 대소문자/표기 다양성을 흡수해서 내부적으로 'KO' / 'EN'으로 정규화
    language: Union[Literal["KO", "EN"], str]
    title: Optional[str] = None

    model_config = ConfigDict(extra="ignore")

    @field_validator("age_range", mode="before")
    def _age_to_str(cls, v):
        # 내부적으로 프롬프트 조립 시 문자열로 쓰기 쉬우려면 문자열화
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
        # Literal에 걸리면 그대로 통과
        return v

# -------- Story / Quiz --------
class StoryPage(BaseModel):
    # 외부에서 'page'로 들어와도 받고, 우리 내부 필드명은 page_no로 사용
    page_no: int = Field(..., alias="page")
    text: str
    id: Optional[int] = None

    model_config = ConfigDict(populate_by_name=True, extra="ignore")

class QA(BaseModel):
    # 'q' / 'a'로 들어와도 받고 내부는 question / answer 사용
    question: str = Field(..., alias="q")
    options: List[str]
    answer: int = Field(..., alias="a")

    model_config = ConfigDict(populate_by_name=True, extra="ignore")

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
    raw_json: str
    moderation: Moderation = Field(default_factory=Moderation)

# -------- Image Generation --------
class GenerateImageRequest(BaseModel):
    text: str

class GenerateImageResponse(BaseModel):
    file_path: str