이 디렉토리는 Python FastAPI 프로젝트입니다.

## API Endpoints

### GET /ai/health

Returns the health status of the AI service.

**Response Example:**
```json
{
  "status": "ok"
}
```

### POST /ai/generate

Generates a poem based on user profile traits and mood.

**Request Example:**
```json
{
  "profile": {
    "traits": {
      "A": 65.0,
      "B": 40.0,
      "C": 80.0
    }
  },
  "mood": {
    "tags": ["기쁨", "벅참"],
    "intensity": 85
  },
  "want": ["poem"]
}
```

**Response Example:**
```json
{
  "poem": "'기쁨'의 감정과 성향 A(65)가 어우러진, 120자 내의 짧은 시입니다. 지금 당신의 마음을 담아보세요."
}
```

## OpenAI / Gemini API 연동

이 서비스는 기본적으로 OpenAI API를 사용하지만, 이미지 생성은 옵션에 따라 Google Gemini 2.5 Flash Image 모델을 사용할 수 있습니다.

### API 키 설정

`.env` 파일에 다음 형식으로 API 키를 추가합니다.

```
OPENAI_API_KEY=sk-your_openai_api_key_here
GEMINI_API_KEY=your_google_ai_studio_key
```

### 모델 설정

- 텍스트/오디오 생성은 기본적으로 `gpt-5-mini` 모델을 사용합니다. `config.py`에서 `OPENAI_MODEL`, `OPENAI_MAX_OUTPUT_TOKENS`, `OPENAI_TEMPERATURE` 값을 조정할 수 있습니다.
- 이미지 생성은 기본적으로 Google Gemini 이미지 모델을 사용합니다. `GEMINI_IMAGE_MODEL` 기본값은 `gemini-3.1-flash-image`이고, 기본 리전은 `GOOGLE_LOCATION=global`입니다. 429 또는 일시 장애 시 `GEMINI_IMAGE_FALLBACK_MODEL` 값으로 한 번 더 시도할 수 있으며, OpenAI 이미지 폴백은 기본 비활성화 상태입니다.

### 샘플 요청/응답 (OpenAI 연동 후)

**Request Example (POST /ai/generate):**
```json
{
  "profile": {
    "traits": {
      "A": 70.0,
      "B": 40.0,
      "C": 60.0
    }
  },
  "mood": {
    "tags": ["기쁨", "벅참"],
    "intensity": 85
  },
  "want": ["poem"]
}
```

**Response Example (POST /ai/generate):**
```json
{
  "poem": "'기쁨'의 감정과 성향 A(70)가 어우러진, 120자 내의 짧은 시입니다. 지금 당신의 마음을 담아보세요.",
  "img_prompt": null,
  "moderation": {
    "safe": true,
    "flags": []
  }
}
```

## API 공통 사항

### 레이트리밋

*   **제한:** IP당 초당 3요청, 분당 60요청으로 제한됩니다.
*   **제외:** `/ai/health` 경로는 레이트리밋에서 제외됩니다.
*   **초과 시 응답:** HTTP 429 (Too Many Requests) 상태 코드와 함께 다음 공통 에러 포맷으로 응답합니다.

### 에러 응답 포맷

모든 API 에러 응답은 다음 JSON 형식을 따릅니다.

```json
{
  "code": "STRING",        // 에러 코드 (예: VALIDATION_ERROR, RATE_LIMITED, INTERNAL_SERVER_ERROR)
  "message": "설명",       // 에러에 대한 간략한 설명
  "requestId": "uuid",     // 요청을 추적하기 위한 고유 ID (X-Request-Id 헤더와 동일)
  "timestamp": "ISO-8601"  // 에러 발생 시간 (ISO 8601 형식)
}
```

### 요청 ID (X-Request-Id)

모든 요청에 대해 고유한 `requestId`가 생성되어 응답 헤더 `X-Request-Id`에 포함됩니다.
