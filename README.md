# Jaramgle Backend

백엔드(Spring Boot)와 AI 파이프라인(FastAPI+Python)이 함께 있는 프로젝트입니다. 자산(이미지/오디오/캐릭터)은 기본적으로 리포지토리 내부 `data/` 아래에 저장되며, 경로는 `.env`로 재정의할 수 있습니다.

## 환경 변수

`.env.example`을 복사하여 필요한 값을 설정하세요.

```bash
cp .env.example .env
```

- `CHARACTER_IMAGE_DIR` (기본 `data/character`)
- `IMAGE_BASE_DIR` (기본 `data/image`)
- `AUDIO_BASE_DIR` (기본 `data/audio`)
- `LLM_PROVIDER` (예: `gemini`)

Java와 Python 모두 경로가 비어 있으면 기본값을 사용하며, Java 측에서 앱 기동 시 `data/*` 디렉토리를 자동 생성합니다.

## 실행(개발)

루트(`jaramgle`)에 있는 `start-dev.sh`가 프론트/백/AI를 모두 실행합니다.

```bash
cd /Users/kyj/jaramgle
./start-dev.sh
```

개별 실행이 필요한 경우:

- 백엔드: `cd jaramgle-backend/backend && ./gradlew bootRun`
- AI 서버: `cd jaramgle-backend/ai-python && uvicorn main:app --reload`
- 프론트엔드: `cd jaramgle-frontend && npm run dev`

## 폴더 구조(요약)

- `backend/` : Spring Boot 앱
- `ai-python/` : 텍스트/이미지/음성 AI 파이프라인
- `data/` : 런타임 자산 저장소 (`.gitignore` 처리, `.gitkeep`만 추적)

