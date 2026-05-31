#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
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
DEFAULT_CAPTION_FONT_SIZE = 34
DEFAULT_CAPTION_MAX_LINES = 3
DEFAULT_CAPTION_MIN_SECONDS = 2.2


@dataclass(frozen=True)
class StoryPage:
    page: int
    image: str
    text: str
    audio: str | None
    duration: float


@dataclass(frozen=True)
class CaptionTrack:
    path: Path
    start: float
    end: float


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
        chunks = split_caption_chunks(
            page.text,
            width=args.width,
            max_lines=args.caption_max_lines,
            font_size=args.caption_font_size,
        )
        duration = max(duration, len(chunks) * args.caption_min_seconds)

        caption_tracks: list[CaptionTrack] = []
        for chunk_index, chunk in enumerate(chunks):
            caption_path = captions_dir / f"page-{page.page:03d}-{chunk_index + 1:02d}.png"
            render_caption_overlay(
                caption_path,
                chunk,
                width=args.width,
                height=args.height,
                title=title if index == 1 and chunk_index == 0 else None,
                font_size=args.caption_font_size,
                max_lines=args.caption_max_lines,
            )
            start, end = caption_window(chunk_index, len(chunks), duration)
            caption_tracks.append(CaptionTrack(path=caption_path, start=start, end=end))

        clip_path = clips_dir / f"page-{page.page:03d}.mp4"
        print(f"  rendering clip ({duration:.2f}s, captions={len(caption_tracks)})")
        render_page_clip(
            ffmpeg=ffmpeg,
            image_path=image_path,
            caption_tracks=caption_tracks,
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
    parser.add_argument("--caption-font-size", type=int, default=DEFAULT_CAPTION_FONT_SIZE)
    parser.add_argument("--caption-max-lines", type=int, default=DEFAULT_CAPTION_MAX_LINES)
    parser.add_argument("--caption-min-seconds", type=float, default=DEFAULT_CAPTION_MIN_SECONDS)
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


def split_caption_chunks(text: str, *, width: int, max_lines: int, font_size: int) -> list[str]:
    normalized = normalize_caption_text(text)
    if not normalized:
        return [""]

    font = load_font(font_size)
    probe = Image.new("RGBA", (width, 100), (0, 0, 0, 0))
    draw = ImageDraw.Draw(probe)
    max_text_width = width - 2 * 72 - 2 * 36

    chunks: list[str] = []
    current = ""
    for unit in split_sentence_units(normalized):
        candidate = f"{current} {unit}".strip() if current else unit
        if caption_fits(draw, candidate, font, max_text_width, max_lines):
            current = candidate
            continue

        if current:
            chunks.append(current)
            current = ""

        if caption_fits(draw, unit, font, max_text_width, max_lines):
            current = unit
        else:
            lines = wrap_text_to_pixels(draw, unit, font, max_text_width)
            for start in range(0, len(lines), max_lines):
                chunks.append(" ".join(lines[start:start + max_lines]).strip())

    if current:
        chunks.append(current)

    return [chunk for chunk in chunks if chunk.strip()] or [normalized]


def normalize_caption_text(text: str) -> str:
    return re.sub(r"\s+", " ", text or "").strip()


def split_sentence_units(text: str) -> list[str]:
    units: list[str] = []
    current: list[str] = []
    index = 0
    quote_pairs = {'"': '"', "“": "”", "'": "'", "‘": "’"}
    sentence_endings = set(".!?。！？")

    while index < len(text):
        char = text[index]
        if char in quote_pairs:
            if current and "".join(current).strip():
                units.append("".join(current).strip())
                current = []

            closing = quote_pairs[char]
            quoted = [char]
            index += 1
            while index < len(text):
                quoted.append(text[index])
                if text[index] == closing:
                    break
                index += 1
            units.append("".join(quoted).strip())
        else:
            current.append(char)
            if char in sentence_endings:
                units.append("".join(current).strip())
                current = []
        index += 1

    if current and "".join(current).strip():
        units.append("".join(current).strip())
    return units


def caption_fits(
    draw: ImageDraw.ImageDraw,
    text: str,
    font: ImageFont.FreeTypeFont | ImageFont.ImageFont,
    max_width: int,
    max_lines: int,
) -> bool:
    return len(wrap_text_to_pixels(draw, text, font, max_width)) <= max_lines


def caption_window(index: int, total: int, duration: float) -> tuple[float, float]:
    if total <= 1:
        return 0.0, duration
    start = duration * index / total
    end = duration * (index + 1) / total
    return start, end


def render_caption_overlay(
    path: Path,
    text: str,
    *,
    width: int,
    height: int,
    title: str | None = None,
    font_size: int = DEFAULT_CAPTION_FONT_SIZE,
    max_lines: int = DEFAULT_CAPTION_MAX_LINES,
) -> None:
    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image, "RGBA")

    font = load_font(font_size)
    title_font = load_font(42)
    max_text_width = width - 2 * 72 - 2 * 36
    caption_lines = wrap_text_to_pixels(draw, text, font, max_text_width)
    while len(caption_lines) > max_lines and font_size > 24:
        font_size -= 2
        font = load_font(font_size)
        caption_lines = wrap_text_to_pixels(draw, text, font, max_text_width)
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


def wrap_text_to_pixels(
    draw: ImageDraw.ImageDraw,
    text: str,
    font: ImageFont.FreeTypeFont | ImageFont.ImageFont,
    max_width: int,
) -> list[str]:
    if not text.strip():
        return [""]

    lines: list[str] = []
    for paragraph in text.splitlines() or [text]:
        current = ""
        for token in re.findall(r"\S+\s*", paragraph):
            candidate = f"{current}{token}" if current else token
            if text_pixel_width(draw, candidate.rstrip(), font) <= max_width:
                current = candidate
                continue

            if current.strip():
                lines.append(current.rstrip())
                current = ""

            if text_pixel_width(draw, token.rstrip(), font) <= max_width:
                current = token
            else:
                current = append_split_token(draw, token.rstrip(), font, max_width, lines)

        if current.strip():
            lines.append(current.rstrip())

    return lines or [""]


def append_split_token(
    draw: ImageDraw.ImageDraw,
    token: str,
    font: ImageFont.FreeTypeFont | ImageFont.ImageFont,
    max_width: int,
    lines: list[str],
) -> str:
    current = ""
    for char in token:
        candidate = current + char
        if text_pixel_width(draw, candidate, font) <= max_width:
            current = candidate
        else:
            if current:
                lines.append(current)
            current = char
    return current


def text_pixel_width(
    draw: ImageDraw.ImageDraw,
    text: str,
    font: ImageFont.FreeTypeFont | ImageFont.ImageFont,
) -> int:
    bbox = draw.textbbox((0, 0), text, font=font)
    return bbox[2] - bbox[0]


def render_page_clip(
    *,
    ffmpeg: str,
    image_path: Path,
    caption_tracks: list[CaptionTrack],
    audio_path: Path | None,
    output_path: Path,
    duration: float,
    width: int,
    height: int,
    fps: int,
) -> None:
    frames = max(1, int(duration * fps))
    filter_parts = [
        f"[0:v]scale={width * 2}:{height * 2}:force_original_aspect_ratio=increase,"
        f"crop={width * 2}:{height * 2},"
        f"zoompan=z='min(zoom+0.0007,1.08)':d={frames}:"
        f"x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s={width}x{height}:fps={fps},"
        f"trim=duration={duration:.3f},setpts=PTS-STARTPTS[base]"
    ]

    previous_label = "base"
    for index, track in enumerate(caption_tracks, start=1):
        input_index = index
        cap_label = f"cap{index}"
        out_label = "v" if index == len(caption_tracks) else f"ov{index}"
        enable = f"between(t\\,{track.start:.3f}\\,{track.end:.3f})"
        filter_parts.append(f"[{input_index}:v]scale={width}:{height},format=rgba[{cap_label}]")
        filter_parts.append(
            f"[{previous_label}][{cap_label}]overlay=0:0:format=auto:enable='{enable}'[{out_label}]"
        )
        previous_label = out_label

    if not caption_tracks:
        filter_parts.append("[base]format=yuv420p[v]")
    else:
        filter_parts.append(f"[{previous_label}]format=yuv420p[v]")
    zoom_filter = ";".join(filter_parts)

    caption_inputs: list[str] = []
    for track in caption_tracks:
        caption_inputs.extend(["-loop", "1", "-i", str(track.path)])
    audio_input_index = 1 + len(caption_tracks)

    if audio_path:
        command = [
            ffmpeg,
            "-y",
            "-loop",
            "1",
            "-i",
            str(image_path),
            *caption_inputs,
            "-i",
            str(audio_path),
            "-filter_complex",
            zoom_filter,
            "-map",
            "[v]",
            "-map",
            f"{audio_input_index}:a:0",
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
            *caption_inputs,
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
            f"{audio_input_index}:a:0",
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
