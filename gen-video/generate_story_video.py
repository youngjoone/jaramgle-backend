#!/usr/bin/env python3
"""
generate_story_video.py
Full pipeline: fairy_tale source JSON → Gemini TTS → video clips → BGM mix → final MP4

Usage:
  python generate_story_video.py --source source/forest-friends-story.json --out output/video.mp4
  python generate_story_video.py --source source/my-story.json --out out/my.mp4 --bgm warm --bgm-volume 0.15
  python generate_story_video.py --source source/my-story.json --out out/my.mp4 --no-bgm
  python generate_story_video.py --source source/my-story.json --out out/my.mp4 --force-tts
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import re
import shutil
import subprocess
import sys
import wave
from io import BytesIO
from pathlib import Path
from typing import Any

from dotenv import load_dotenv
import requests

# ─────────────────────────── constants ────────────────────────────────────────

BGM_DIR = Path(__file__).parent / "bgm"
BGM_DEFAULT = "magical"
BGM_MOODS = {"magical", "warm", "adventure", "peaceful", "cheerful", "mysterious", "dreamy"}

TTS_MODEL = "gemini-3.1-flash-tts-preview"
TTS_VOICE = "Kore"       # 한국어 여성 나레이터
TTS_SAMPLE_RATE = 24000  # Gemini TTS LINEAR16 출력 기본 샘플레이트

BGM_VOLUME_DEFAULT = 0.12  # 나레이션 대비 BGM 볼륨 (12%)
DEFAULT_PAGE_DURATION = 8.0

TTS_NARRATOR_PROMPT = (
    "다음 동화 본문을 따뜻하고 부드러운 한국어 어린이 동화 나레이터 목소리로 "
    "자연스럽게 읽어주세요. 아이들이 듣기 편하도록 또박또박, 감정을 살려서:\n\n"
)

# ─────────────────────────── env & setup ──────────────────────────────────────

def load_api_key() -> str:
    for dotenv in [
        Path(__file__).parent / ".env",
        Path(__file__).parent.parent / "ai-python" / ".env",
        Path(__file__).parent.parent / ".env",
    ]:
        if dotenv.exists():
            load_dotenv(dotenv)
            break
    else:
        load_dotenv()

    key = os.getenv("GEMINI_API_KEY", "").strip()
    if not key:
        sys.exit("ERROR: GEMINI_API_KEY not set. Add it to .env or set the environment variable.")
    return key


def resolve_ffmpeg() -> str:
    env_bin = os.getenv("FFMPEG_BIN", "").strip()
    if env_bin:
        return env_bin
    found = shutil.which("ffmpeg")
    if found:
        return found
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception as exc:
        raise RuntimeError("ffmpeg not found. Install it or run: pip install imageio-ffmpeg") from exc


def run_cmd(cmd: list[str], label: str = "") -> None:
    tag = f"[{label}] " if label else ""
    result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False)
    if result.returncode != 0:
        sys.stderr.write(f"{tag}FAILED (rc={result.returncode})\n")
        sys.stderr.write(result.stderr[-4000:] + "\n")
        raise RuntimeError(f"{tag}Command failed: {' '.join(cmd[:5])} ...")


def resolve_bgm_mood(mood: str | None) -> str:
    key = (mood or "").strip().lower()
    return key if key in BGM_MOODS else BGM_DEFAULT


def find_bgm_file(mood: str) -> Path | None:
    path = BGM_DIR / f"{mood}.mp3"
    if path.exists():
        return path
    fallback = BGM_DIR / f"{BGM_DEFAULT}.mp3"
    return fallback if fallback.exists() else None


# ─────────────────────────── TTS via Gemini REST ──────────────────────────────

def parse_sample_rate(mime_type: str) -> int:
    """audio/L16;codec=audio/pcm;rate=24000 → 24000"""
    m = re.search(r"rate=(\d+)", mime_type or "")
    return int(m.group(1)) if m else TTS_SAMPLE_RATE


def pcm_to_wav(pcm: bytes, *, sample_rate: int, channels: int = 1, sampwidth: int = 2) -> bytes:
    """Raw LINEAR16 PCM → WAV bytes."""
    buf = BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(channels)
        wf.setsampwidth(sampwidth)
        wf.setframerate(sample_rate)
        wf.writeframes(pcm)
    return buf.getvalue()


def gemini_tts(text: str, api_key: str, voice: str = TTS_VOICE) -> bytes:
    """Gemini TTS REST API 호출 → WAV bytes 반환."""
    url = (
        f"https://generativelanguage.googleapis.com/v1beta/models"
        f"/{TTS_MODEL}:generateContent?key={api_key}"
    )
    payload: dict[str, Any] = {
        "contents": [{"parts": [{"text": TTS_NARRATOR_PROMPT + text}]}],
        "generationConfig": {
            "responseModalities": ["AUDIO"],
            "speechConfig": {
                "voiceConfig": {
                    "prebuiltVoiceConfig": {"voiceName": voice}
                }
            },
        },
    }
    resp = requests.post(url, json=payload, timeout=120)
    try:
        resp.raise_for_status()
    except requests.HTTPError as e:
        body = resp.text[:500]
        raise RuntimeError(f"Gemini TTS API error {resp.status_code}: {body}") from e

    data = resp.json()
    part = data["candidates"][0]["content"]["parts"][0]["inlineData"]
    pcm = base64.b64decode(part["data"])
    rate = parse_sample_rate(part.get("mimeType", ""))
    return pcm_to_wav(pcm, sample_rate=rate)


# ─────────────────────────── source JSON parsing ──────────────────────────────

def load_fairy_tale(source_path: Path) -> dict[str, Any]:
    with source_path.open("r", encoding="utf-8") as f:
        data = json.load(f)
    ft = data.get("fairy_tale")
    if not isinstance(ft, dict):
        raise ValueError("Source JSON must contain a 'fairy_tale' object.")
    return ft


# ─────────────────────────── TTS generation pass ──────────────────────────────

def generate_audio_for_pages(
    fairy_tale: dict[str, Any],
    source_dir: Path,
    audio_dir: Path,
    api_key: str,
    voice: str = TTS_VOICE,
    force: bool = False,
) -> list[dict[str, Any]]:
    """TTS 생성 후 페이지별 메타데이터(이미지·오디오 경로 포함) 리스트 반환."""
    story_bgm = resolve_bgm_mood(fairy_tale.get("bgm"))
    pages: list[dict[str, Any]] = []

    for i, chap in enumerate(fairy_tale.get("chapters") or [], start=1):
        if not isinstance(chap, dict):
            continue

        num = int(chap.get("chapter_number") or i)
        text = (chap.get("script") or "").strip()
        img_val = chap.get("image") or f"{num}.png"
        img_path = Path(img_val) if Path(img_val).is_absolute() else source_dir / img_val

        if not img_path.exists():
            raise FileNotFoundError(f"Chapter {num} image not found: {img_path}")

        audio_path = audio_dir / f"page-{num:03d}.wav"

        if audio_path.exists() and not force:
            print(f"  page {num}: [TTS cached] {audio_path.name}")
        else:
            print(f"  page {num}: [TTS] generating ({len(text)} chars, voice={voice})...")
            wav = gemini_tts(text, api_key, voice=voice)
            audio_path.write_bytes(wav)
            print(f"           → {audio_path.name} ({len(wav) // 1024} KB)")

        motion = chap.get("video_motion") if isinstance(chap.get("video_motion"), dict) else {}
        chapter_bgm = resolve_bgm_mood(chap.get("bgm")) if chap.get("bgm") else story_bgm

        pages.append({
            "page": num,
            "title": chap.get("chapter_title") or f"Chapter {i}",
            "image": str(img_path.resolve()),
            "text": text,
            "audio": str(audio_path.resolve()),
            "motionPrompt": motion.get("prompt_en") or "",
            "motionDescription": motion.get("description_kr") or "",
            "duration": DEFAULT_PAGE_DURATION,
            "bgm": chapter_bgm,
        })

    return pages


# ─────────────────────────── video rendering ──────────────────────────────────

def render_clips(story_json_path: Path, raw_video_path: Path, clips_work_dir: Path) -> None:
    """story_video.py 호출 → 클립 concat 원본 영상 생성."""
    script = Path(__file__).parent / "story_video.py"
    run_cmd(
        [sys.executable, str(script),
         "--story", str(story_json_path),
         "--out", str(raw_video_path),
         "--workdir", str(clips_work_dir)],
        label="clips",
    )


def mix_bgm_into_video(
    ffmpeg: str,
    raw_video: Path,
    bgm_file: Path,
    out_path: Path,
    bgm_volume: float,
) -> None:
    """나레이션 영상 위에 BGM을 낮은 볼륨으로 혼합 → 최종 MP4."""
    # amix: duration=first → 나레이션이 끝나면 영상도 종료 (BGM이 더 길어도 잘림)
    # dropout_transition: BGM이 끊길 때 자연스럽게 fade
    run_cmd(
        [ffmpeg, "-y",
         "-i", str(raw_video),
         "-stream_loop", "-1", "-i", str(bgm_file),
         "-filter_complex",
         (
             f"[0:a]volume=1.0[nar];"
             f"[1:a]volume={bgm_volume:.4f}[bgm];"
             "[nar][bgm]amix=inputs=2:duration=first:dropout_transition=3[mixed]"
         ),
         "-map", "0:v",
         "-map", "[mixed]",
         "-c:v", "copy",
         "-c:a", "aac", "-b:a", "192k",
         "-movflags", "+faststart",
         str(out_path)],
        label="bgm-mix",
    )


# ─────────────────────────── CLI ──────────────────────────────────────────────

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="fairy_tale source JSON → Gemini TTS → video → BGM → final MP4",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
moods: magical | warm | adventure | peaceful | cheerful | mysterious | dreamy

examples:
  python generate_story_video.py --source source/forest-friends-story.json --out output/video.mp4
  python generate_story_video.py --source source/story.json --out out/v.mp4 --bgm warm
  python generate_story_video.py --source source/story.json --out out/v.mp4 --no-bgm
  python generate_story_video.py --source source/story.json --out out/v.mp4 --force-tts
        """,
    )
    p.add_argument("--source", required=True, help="fairy_tale source JSON 경로")
    p.add_argument("--out", required=True, help="출력 MP4 경로")
    p.add_argument("--workdir", help="임시 작업 디렉토리 (기본: --out 옆에 work/)")
    p.add_argument("--voice", default=TTS_VOICE,
                   help=f"Gemini TTS 음성 이름 (default: {TTS_VOICE}). 예: Kore, Puck, Charon")
    p.add_argument("--bgm", help="BGM 분위기 override (소스 JSON의 bgm 필드보다 우선)")
    p.add_argument("--bgm-volume", type=float, default=BGM_VOLUME_DEFAULT,
                   help=f"BGM 볼륨 0.0~1.0 (default: {BGM_VOLUME_DEFAULT})")
    p.add_argument("--no-bgm", action="store_true", help="BGM 혼합 건너뜀")
    p.add_argument("--force-tts", action="store_true", help="캐시된 오디오 무시하고 TTS 재생성")
    return p.parse_args()


def main() -> int:
    args = parse_args()   # --help 먼저 처리
    api_key = load_api_key()

    source_path = Path(args.source).expanduser().resolve()
    out_path = Path(args.out).expanduser().resolve()
    work_dir = (
        Path(args.workdir).expanduser().resolve()
        if args.workdir
        else out_path.parent / "work"
    )

    audio_dir = work_dir / "audio"
    clips_dir = work_dir / "clips"
    for d in (audio_dir, clips_dir, out_path.parent):
        d.mkdir(parents=True, exist_ok=True)

    ffmpeg = resolve_ffmpeg()
    fairy_tale = load_fairy_tale(source_path)
    title = fairy_tale.get("book_title") or "story"
    chapters = fairy_tale.get("chapters") or []

    print(f"\n{'='*50}")
    print(f"  {title}  ({len(chapters)} chapters)")
    print(f"{'='*50}")

    # ── 1. Gemini TTS ──────────────────────────────────────
    print(f"\n[1/3] TTS 음성 생성 (Gemini / voice={args.voice})")
    pages = generate_audio_for_pages(
        fairy_tale,
        source_dir=source_path.parent,
        audio_dir=audio_dir,
        api_key=api_key,
        voice=args.voice,
        force=args.force_tts,
    )

    # BGM 결정: CLI > 소스 story 레벨 > 기본값
    story_bgm = resolve_bgm_mood(args.bgm or fairy_tale.get("bgm"))

    story_json: dict[str, Any] = {
        "title": title,
        "bgm": story_bgm,
        "pages": pages,
    }
    story_json_path = work_dir / "story.json"
    story_json_path.write_text(json.dumps(story_json, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n  story.json saved → {story_json_path}")

    # ── 2. 영상 클립 렌더링 ─────────────────────────────────
    raw_video_path = work_dir / "raw_video.mp4"
    print(f"\n[2/3] 영상 렌더링 (story_video.py)")
    render_clips(story_json_path, raw_video_path, clips_dir)
    print(f"  raw video → {raw_video_path}")

    # ── 3. BGM 혼합 ─────────────────────────────────────────
    if args.no_bgm:
        shutil.copy2(raw_video_path, out_path)
        print(f"\n[3/3] BGM 건너뜀. 최종 출력: {out_path}")
    else:
        bgm_file = find_bgm_file(story_bgm)
        if bgm_file:
            print(f"\n[3/3] BGM 혼합 (mood={story_bgm}, vol={args.bgm_volume}, file={bgm_file.name})")
            mix_bgm_into_video(ffmpeg, raw_video_path, bgm_file, out_path, args.bgm_volume)
        else:
            shutil.copy2(raw_video_path, out_path)
            print(f"\n[3/3] BGM 파일 없음 (mood={story_bgm}). BGM 없이 출력.")

    print(f"\n{'='*50}")
    print(f"  완료: {out_path}")
    print(f"{'='*50}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
