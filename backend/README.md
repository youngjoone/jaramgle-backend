# Jaramgle Backend (Spring Boot)

Jaramgle API 서버입니다.  
동화 생성/스토리북/공유/지갑/커리큘럼/관리자 API를 제공합니다.

## 기술 스택

- Java 17, Spring Boot 3.3
- Spring Security (JWT + OAuth2)
- Spring Data JPA, Flyway
- WebClient (AI 서버 연동)
- Bucket4j (API rate limit)

## 연동 구성

- API 서버: `backend`
- AI 서버: `../ai-python` (FastAPI)
- 프론트: `../../jaramgle-frontend`

## 실행

### 1) 로컬 프로필(H2) 실행

```bash
cd /Users/kyj/jaramgle/jaramgle-backend/backend
./gradlew bootRun -Dspring.profiles.active=local
```

### 2) Docker 통합 실행 (권장)

```bash
cd /Users/kyj/jaramgle
docker compose -f docker-compose.dev.yml up -d
```

## 주요 엔드포인트

- 인증/사용자: `/api/auth/**`, `/api/me`
- 동화/스토리북: `/api/stories/**`, `/api/stories/{id}/storybook/**`
- 공유: `/api/public/shared-stories/**`, `/api/stories/{id}/share`
- 커리큘럼: `/api/curriculums/**`
- 관리자: `/api/admin/**`

Swagger:

- `http://localhost:8080/swagger-ui/index.html`

## 커리큘럼 Job 동작 요약

- 상태: `NOT_STARTED -> PENDING -> RUNNING -> SUCCEEDED/PARTIAL_SUCCEEDED/FAILED/FAILED_TIMEOUT`
- 실패 시 자동 재시도 1회(백오프 적용)
- 수동 재시도 정책/과금은 주차 기준
- 실패 중간 생성 스토리는 자동 정리(soft-delete)

## 관리자 유지보수 API

고아 커리큘럼 스토리(어느 주차에도 연결되지 않은 `origin=CURRICULUM`)를 점검/정리할 수 있습니다.

- 미리보기: `GET /api/admin/maintenance/curriculum-orphans`
- 정리 실행: `POST /api/admin/maintenance/curriculum-orphans/cleanup`

## 환경 변수/설정

핵심 설정 파일:

- `src/main/resources/application.yml`
- `src/main/resources/application-local.yml`
- `src/main/resources/application-prod.yml`

권장:

- 비밀정보(OAuth secret, DB password, JWT secret, 서비스 계정 키)는 환경 변수/시크릿 매니저로 분리
- 저장소에는 샘플 값만 유지

## 테스트/검증

```bash
cd /Users/kyj/jaramgle/jaramgle-backend/backend
./gradlew test
./gradlew compileJava
```

참고: 일부 외부 AI 의존 통합 테스트는 기본 비활성화 상태일 수 있습니다.

