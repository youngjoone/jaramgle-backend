# gen-video

동화 이미지와 대본을 MP4 영상으로 만드는 실험용 Python 작업 폴더입니다.

현재 목표는 두 단계입니다.

1. 로컬 합성 MVP: 이미지 + 대본 + 선택 음성을 FFmpeg로 이어붙여 동화 영상 생성
2. Leonardo 연동: 페이지 이미지를 Leonardo 이미지-투-비디오 클립으로 만든 뒤 최종 MP4로 합성

## 설치

```bash
cd gen-video
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

`ffmpeg`가 시스템에 없어도 `imageio-ffmpeg`가 제공하는 바이너리를 사용합니다.

## 로컬 영상 생성

```bash
python story_video.py \
  --story story.sample.json \
  --out out/fairy-tale.mp4 \
  --workdir out/work
```

입력 JSON 형식:

```json
{
  "title": "달빛 숲의 약속",
  "pages": [
    {
      "page": 1,
      "image": "/absolute/path/to/page-1.png",
      "text": "첫 번째 장면의 대본입니다.",
      "audio": "/absolute/path/to/page-1.wav",
      "duration": 6
    }
  ]
}
```

- `image`는 필수입니다.
- `text`는 캡션 이미지로 영상 하단에 렌더링됩니다.
- `audio`가 있으면 오디오 길이를 우선 사용합니다.
- `duration`은 오디오가 없을 때 사용되는 페이지 길이입니다.
- `image`와 `audio`는 로컬 경로 또는 `http(s)` URL을 사용할 수 있습니다.

## Leonardo 실험

`.env.example`을 복사해서 `.env`를 만들고 `LEONARDO_API_KEY`를 설정합니다.

```bash
cp .env.example .env
```

`leonardo_client.py`는 다음 작업을 담당하는 클라이언트 뼈대입니다.

- 로컬 이미지 업로드용 presigned URL 생성
- 이미지 파일 업로드
- LTX 2.3 이미지-투-비디오 generation 생성
- generation 상태 polling

동화 소스 JSON을 영상 입력 JSON으로 변환합니다.

```bash
python convert_fairy_tale_source.py \
  --source source/forest-friends-story.json \
  --out out/forest-friends-story-video-input.json \
  --absolute
```

Leonardo에 실제 요청하기 전 dry-run으로 페이지별 요청 구성을 확인합니다.

```bash
python leonardo_story_video.py \
  --story out/forest-friends-story-video-input.json \
  --out out/forest-friends-leonardo.mp4 \
  --workdir out/forest-friends-leonardo-work \
  --dry-run
```

API 키가 준비되면 Hailuo 2.3 Fast로 페이지별 클립을 생성하고 하나로 합칩니다.

```bash
python leonardo_story_video.py \
  --story out/forest-friends-story-video-input.json \
  --out out/forest-friends-leonardo.mp4 \
  --workdir out/forest-friends-leonardo-work \
  --model hailuo-2_3-fast \
  --fallback-model hailuo-2_3 \
  --mode RESOLUTION_768 \
  --duration 6 \
  --width 1376 \
  --height 768 \
  --reuse
```

현재 테스트에서는 `hailuo-2_3-fast`가 validation 오류를 반환했고, `hailuo-2_3` 일반 모델은 동일 start frame 요청으로 정상 생성되었습니다. 스크립트는 Fast를 먼저 시도한 뒤 실패하면 fallback 모델로 재시도합니다.

작업 결과:

- `out/forest-friends-leonardo-work/clips/page-001.mp4`처럼 페이지별 클립 저장
- `out/forest-friends-leonardo-work/responses/*.json`에 Leonardo 응답 저장
- `out/forest-friends-leonardo.mp4` 최종 합본 저장

실제 프로덕션 적용 전에는 Leonardo 문서의 최신 request/response 필드명을 다시 한 번 맞춰야 합니다. 이 폴더는 먼저 파이프라인을 검증하기 위한 실험 공간입니다.

## 다음 구현 후보

- Spring 백엔드에 `story_videos` / `storybook_page_videos` 테이블 추가
- `POST /api/stories/{id}/video` 생성 API 추가
- AI Python 서비스에 `/ai/generate-story-video` 엔드포인트 추가
- 작업 상태 `PENDING/RUNNING/COMPLETE/FAILED` 관리
- Leonardo 클립 생성 실패 시 로컬 Ken Burns 영상으로 fallback
