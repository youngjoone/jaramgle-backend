#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import requests
from dotenv import load_dotenv
from PIL import Image, ImageDraw, ImageFont


DEFAULT_WIDTH = 1280
DEFAULT_HEIGHT = 720
DEFAULT_FPS = 30
DEFAULT_PAGE_DURATION = 6.0
MAX_AUDIO_PAGE_DURATION = 90.0


@dataclass(frozen=True)
class StoryPage:
    page: int
    image: str
    text: str
    audio: str | None
    duration: float


def main() -> int:
    load_dotenv()
    args = parse_args()

    story_path = Path(args.story).expanduser().resolve()
    out_path = Path(args.out).expanduser().resolve()
    workdir = Path(args.workdir).expanduser().resolve() if args.workdir else out_path.parent / "work"
    workdir.mkdir(parents=True, exist_ok=True)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    ffmpeg = resolve_ffmpeg()
    story = load_story(story_path)
    pages = parse_pages(story)
    if not pages:
        raise SystemExit("No pages found in story JSON.")

    title = str(story.get("title") or "story")
    print(f"Generating video: {title} ({len(pages)} pages)")

    media_dir = workdir / "media"
    captions_dir = workdir / "captions"
    clips_dir = workdir / "clips"
    for directory in (media_dir, captions_dir, clips_dir):
        directory.mkdir(parents=True, exist_ok=True)

    clip_paths: list[Path] = []
    for index, page in enumerate(pages, start=1):
        print(f"- page {page.page}: preparing media")
        image_path = resolve_media(page.image, media_dir, f"page-{page.page}-image")
        audio_path = resolve_media(page.audio, media_dir, f"page-{page.page}-audio") if page.audio else None
        duration = resolve_page_duration(ffmpeg, audio_path, page.duration)

        caption_path = captions_dir / f"page-{page.page:03d}.png"
        render_caption_overlay(
            caption_path,
            page.text,
            width=args.width,
            height=args.height,
            title=title if index == 1 else None,
        )

        clip_path = clips_dir / f"page-{page.page:03d}.mp4"
        print(f"  rendering clip ({duration:.2f}s)")
        render_page_clip(
            ffmpeg=ffmpeg,
            image_path=image_path,
            caption_path=caption_path,
            audio_path=audio_path,
            output_path=clip_path,
            duration=duration,
            width=args.width,
            height=args.height,
            fps=args.fps,
        )
        clip_paths.append(clip_path)

    concat_file = workdir / "concat.txt"
    concat_file.write_text(
        "".join(f"file '{clip.as_posix()}'\n" for clip in clip_paths),
        encoding="utf-8",
    )

    print("Concatenating final video")
    run(
        [
            ffmpeg,
            "-y",
            "-f",
            "concat",
            "-safe",
            "0",
            "-i",
            str(concat_file),
            "-c",
            "copy",
            str(out_path),
        ]
    )
    print(f"Done: {out_path}")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Create a fairy-tale MP4 from page images, text, and optional audio.")
    parser.add_argument("--story", required=True, help="Story JSON path.")
    parser.add_argument("--out", required=True, help="Output MP4 path.")
    parser.add_argument("--workdir", help="Temporary work directory.")
    parser.add_argument("--width", type=int, default=DEFAULT_WIDTH)
    parser.add_argument("--height", type=int, default=DEFAULT_HEIGHT)
    parser.add_argument("--fps", type=int, default=DEFAULT_FPS)
    return parser.parse_args()


def resolve_ffmpeg() -> str:
    env_bin = os.getenv("FFMPEG_BIN", "").strip()
    if env_bin:
        return env_bin

    system_bin = shutil.which("ffmpeg")
    if system_bin:
        return system_bin

    try:
        import imageio_ffmpeg

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception as exc:
        raise RuntimeError("ffmpeg not found. Run `pip install -r requirements.txt` in gen-video first.") from exc


def load_story(story_path: Path) -> dict[str, Any]:
    with story_path.open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, dict):
        raise ValueError("Story JSON must be an object.")
    return data


def parse_pages(story: dict[str, Any]) -> list[StoryPage]:
    raw_pages = story.get("pages") or []
    pages: list[StoryPage] = []
    for index, raw in enumerate(raw_pages, start=1):
        if not isinstance(raw, dict):
            continue
        image = raw.get("image") or raw.get("imageUrl") or raw.get("image_url")
        if not image:
            raise ValueError(f"Page {index} is missing image/imageUrl.")
        page_no = int(raw.get("page") or raw.get("pageNo") or raw.get("page_no") or index)
        text = str(raw.get("text") or "")
        audio = raw.get("audio") or raw.get("audioUrl") or raw.get("audio_url")
        duration = float(raw.get("duration") or DEFAULT_PAGE_DURATION)
        pages.append(StoryPage(page=page_no, image=str(image), text=text, audio=str(audio) if audio else None, duration=duration))
    return sorted(pages, key=lambda item: item.page)


def resolve_media(source: str | None, media_dir: Path, stem: str) -> Path:
    if not source:
        raise ValueError("Media source is required.")

    parsed = urlparse(source)
    if parsed.scheme in {"http", "https"}:
        suffix = Path(parsed.path).suffix or ".bin"
        target = media_dir / f"{safe_name(stem)}{suffix}"
        if not target.exists():
            response = requests.get(source, timeout=60)
            response.raise_for_status()
            target.write_bytes(response.content)
        return target

    path = Path(source).expanduser()
    if not path.is_absolute():
        path = Path.cwd() / path
    path = path.resolve()
    if not path.exists():
        raise FileNotFoundError(path)
    return path


def safe_name(value: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_.-]+", "-", value).strip("-") or "media"


def resolve_page_duration(ffmpeg: str, audio_path: Path | None, fallback_duration: float) -> float:
    if not audio_path:
        return max(1.0, fallback_duration)
    duration = probe_duration(ffmpeg, audio_path)
    if duration is None:
        return max(1.0, fallback_duration)
    return min(max(1.0, duration + 0.25), MAX_AUDIO_PAGE_DURATION)


def probe_duration(ffmpeg: str, media_path: Path) -> float | None:
    completed = subprocess.run(
        [ffmpeg, "-hide_banner", "-i", str(media_path), "-f", "null", "-"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )
    text = f"{completed.stdout}\n{completed.stderr}"
    match = re.search(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)", text)
    if not match:
        return None
    hours, minutes, seconds = match.groups()
    return int(hours) * 3600 + int(minutes) * 60 + float(seconds)


def render_caption_overlay(path: Path, text: str, *, width: int, height: int, title: str | None = None) -> None:
    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image, "RGBA")

    font = load_font(34)
    title_font = load_font(42)
    caption_lines = wrap_text(text, width=30)
    caption_text = "\n".join(caption_lines)

    box_margin = 72
    box_padding_x = 36
    box_padding_y = 26
    line_height = 46
    box_height = max(120, len(caption_lines) * line_height + box_padding_y * 2)
    box_top = height - box_height - 52
    box_left = box_margin
    box_right = width - box_margin
    box_bottom = height - 52

    draw.rounded_rectangle(
        (box_left, box_top, box_right, box_bottom),
        radius=26,
        fill=(12, 18, 24, 178),
    )
    draw.multiline_text(
        (box_left + box_padding_x, box_top + box_padding_y),
        caption_text,
        font=font,
        fill=(255, 255, 255, 245),
        spacing=8,
    )

    if title:
        draw.text((64, 54), title, font=title_font, fill=(255, 255, 255, 235))

    image.save(path)


def load_font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    candidates = [
        "/System/Library/Fonts/AppleSDGothicNeo.ttc",
        "/System/Library/Fonts/Supplemental/AppleGothic.ttf",
        "/Library/Fonts/Arial Unicode.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ]
    for candidate in candidates:
        if Path(candidate).exists():
            try:
                return ImageFont.truetype(candidate, size=size)
            except OSError:
                continue
    return ImageFont.load_default()


def wrap_text(text: str, *, width: int) -> list[str]:
    if not text.strip():
        return [""]
    lines: list[str] = []
    for paragraph in text.splitlines():
        wrapped = textwrap.wrap(paragraph, width=width, break_long_words=False, replace_whitespace=False)
        lines.extend(wrapped or [""])
    return lines[:4]


def render_page_clip(
    *,
    ffmpeg: str,
    image_path: Path,
    caption_path: Path,
    audio_path: Path | None,
    output_path: Path,
    duration: float,
    width: int,
    height: int,
    fps: int,
) -> None:
    frames = max(1, int(duration * fps))
    zoom_filter = (
        f"[0:v]scale={width * 2}:{height * 2}:force_original_aspect_ratio=increase,"
        f"crop={width * 2}:{height * 2},"
        f"zoompan=z='min(zoom+0.0007,1.08)':d={frames}:"
        f"x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s={width}x{height}:fps={fps},"
        f"trim=duration={duration:.3f},setpts=PTS-STARTPTS[bg];"
        f"[1:v]scale={width}:{height},format=rgba[cap];"
        f"[bg][cap]overlay=0:0:format=auto,format=yuv420p[v]"
    )

    if audio_path:
        command = [
            ffmpeg,
            "-y",
            "-loop",
            "1",
            "-i",
            str(image_path),
            "-loop",
            "1",
            "-i",
            str(caption_path),
            "-i",
            str(audio_path),
            "-filter_complex",
            zoom_filter,
            "-map",
            "[v]",
            "-map",
            "2:a:0",
            "-t",
            f"{duration:.3f}",
            "-c:v",
            "libx264",
            "-preset",
            "medium",
            "-crf",
            "20",
            "-c:a",
            "aac",
            "-b:a",
            "160k",
            "-movflags",
            "+faststart",
            str(output_path),
        ]
    else:
        command = [
            ffmpeg,
            "-y",
            "-loop",
            "1",
            "-i",
            str(image_path),
            "-loop",
            "1",
            "-i",
            str(caption_path),
            "-f",
            "lavfi",
            "-t",
            f"{duration:.3f}",
            "-i",
            "anullsrc=channel_layout=stereo:sample_rate=44100",
            "-filter_complex",
            zoom_filter,
            "-map",
            "[v]",
            "-map",
            "2:a:0",
            "-t",
            f"{duration:.3f}",
            "-c:v",
            "libx264",
            "-preset",
            "medium",
            "-crf",
            "20",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-movflags",
            "+faststart",
            str(output_path),
        ]
    run(command)


def run(command: list[str]) -> None:
    completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False)
    if completed.returncode != 0:
        sys.stderr.write(completed.stderr)
        raise RuntimeError(f"Command failed: {' '.join(command)}")


if __name__ == "__main__":
    raise SystemExit(main())
