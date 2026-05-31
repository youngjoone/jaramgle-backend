#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

from dotenv import load_dotenv
from PIL import Image, ImageOps

from leonardo_client import LeonardoClient
from story_video import resolve_ffmpeg


def load_local_env() -> None:
    load_dotenv(Path(__file__).with_name(".env"))
    load_dotenv()


def main() -> int:
    load_local_env()
    args = parse_args()

    story_path = Path(args.story).expanduser().resolve()
    out_path = Path(args.out).expanduser().resolve()
    workdir = Path(args.workdir).expanduser().resolve() if args.workdir else out_path.parent / "leonardo-work"
    clips_dir = workdir / "clips"
    responses_dir = workdir / "responses"
    upload_dir = workdir / "upload-images"
    clips_dir.mkdir(parents=True, exist_ok=True)
    responses_dir.mkdir(parents=True, exist_ok=True)
    upload_dir.mkdir(parents=True, exist_ok=True)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    story = load_story(story_path)
    pages = story.get("pages") or []
    if not isinstance(pages, list) or not pages:
        raise SystemExit("Story JSON must contain non-empty pages array.")

    print(f"Leonardo story video pipeline: {story.get('title') or story_path.stem} ({len(pages)} pages)")

    if args.dry_run:
        write_dry_run_manifest(pages, workdir, args)
        print(f"Dry run manifest written: {workdir / 'dry-run-manifest.json'}")
        return 0

    client = LeonardoClient()
    clip_paths: list[Path] = []
    for index, page in enumerate(pages, start=1):
        if not isinstance(page, dict):
            continue
        page_no = int(page.get("page") or index)
        clip_path = clips_dir / f"page-{page_no:03d}.mp4"
        if args.reuse and clip_path.exists() and clip_path.stat().st_size > 0:
            print(f"- page {page_no}: reusing {clip_path}")
            clip_paths.append(clip_path)
            continue

        image_path = resolve_image_path(page, story_path)
        upload_image_path = prepare_upload_image(
            image_path,
            upload_dir,
            page_no,
            args.max_upload_dimension,
            args.upload_aspect_ratio,
        )
        prompt = resolve_motion_prompt(page)
        if not prompt:
            raise ValueError(f"Page {page_no} has no motionPrompt or text.")

        print(f"- page {page_no}: uploading image {upload_image_path.name}")
        image_id = client.upload_image(upload_image_path)

        print(f"  creating Leonardo generation")
        create_payload, used_model = create_generation_with_fallback(
            client=client,
            image_id=image_id,
            prompt=prompt,
            args=args,
        )
        write_json(responses_dir / f"page-{page_no:03d}-create.json", create_payload)
        generation_id = LeonardoClient.extract_generation_id(create_payload)
        print(f"  generation id: {generation_id} ({used_model})")

        print(f"  waiting for video")
        video_url = client.wait_for_video_url(
            generation_id,
            timeout_seconds=args.timeout,
            poll_seconds=args.poll,
        )
        write_json(responses_dir / f"page-{page_no:03d}-result.json", {"generationId": generation_id, "videoUrl": video_url})

        print(f"  downloading clip")
        client.download(video_url, clip_path)
        clip_paths.append(clip_path)

    if not clip_paths:
        raise SystemExit("No clips generated.")

    print("Merging clips")
    concat_videos(resolve_ffmpeg(), clip_paths, out_path, workdir / "concat.txt")
    print(f"Done: {out_path}")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate page videos with Leonardo and merge them into one MP4.")
    parser.add_argument("--story", required=True, help="story_video.py input JSON with pages[].image and pages[].motionPrompt.")
    parser.add_argument("--out", required=True, help="Final merged MP4 path.")
    parser.add_argument("--workdir", help="Working directory for clips/responses.")
    parser.add_argument("--model", default=os.getenv("LEONARDO_VIDEO_MODEL", "hailuo-2_3-fast"))
    parser.add_argument("--fallback-model", default=os.getenv("LEONARDO_FALLBACK_VIDEO_MODEL", "hailuo-2_3"))
    parser.add_argument("--mode", default=os.getenv("LEONARDO_VIDEO_MODE", "RESOLUTION_768"))
    parser.add_argument("--width", type=int, default=int(os.getenv("LEONARDO_VIDEO_WIDTH", "0")))
    parser.add_argument("--height", type=int, default=int(os.getenv("LEONARDO_VIDEO_HEIGHT", "0")))
    parser.add_argument("--duration", type=int, default=int(os.getenv("LEONARDO_VIDEO_DURATION", "6")))
    parser.add_argument("--style-ids", default=os.getenv("LEONARDO_STYLE_IDS", ""), type=parse_style_ids)
    parser.add_argument("--max-upload-dimension", type=int, default=int(os.getenv("LEONARDO_MAX_UPLOAD_DIMENSION", "2048")))
    parser.add_argument("--upload-aspect-ratio", default=os.getenv("LEONARDO_UPLOAD_ASPECT_RATIO", "16:9"))
    parser.add_argument("--public", action="store_true", help="Create public Leonardo generations.")
    parser.add_argument("--prompt-enhance", choices=("ON", "OFF"), help="Optional prompt enhancement for supported Hailuo models.")
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--poll", type=int, default=8)
    parser.add_argument("--reuse", action="store_true", help="Reuse existing page clips in workdir/clips.")
    parser.add_argument("--dry-run", action="store_true", help="Write request manifest without calling Leonardo.")
    return parser.parse_args()


def create_generation_with_fallback(
    *,
    client: LeonardoClient,
    image_id: str,
    prompt: str,
    args: argparse.Namespace,
) -> tuple[dict[str, Any], str]:
    models = [args.model]
    if args.fallback_model and args.fallback_model not in models:
        models.append(args.fallback_model)

    last_error: Exception | None = None
    for model in models:
        try:
            payload = client.create_hailuo_image_to_video(
                image_id=image_id,
                prompt=prompt,
                duration=args.duration,
                width=args.width,
                height=args.height,
                model=model,
                mode=args.mode,
                style_ids=args.style_ids,
                public=args.public,
                prompt_enhance=args.prompt_enhance,
            )
            return payload, model
        except Exception as exc:
            last_error = exc
            if model == models[-1]:
                break
            print(f"  {model} failed; retrying with {models[-1]}")

    raise RuntimeError("Failed to create Leonardo generation.") from last_error


def parse_style_ids(value: str | list[str] | None) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [item.strip() for item in str(value).split(",") if item.strip()]


def load_story(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, dict):
        raise ValueError("Story JSON must be an object.")
    return data


def resolve_image_path(page: dict[str, Any], story_path: Path) -> Path:
    value = page.get("image") or page.get("imageUrl") or page.get("image_url")
    if not value:
        raise ValueError(f"Page {page.get('page')} is missing image path.")
    path = Path(str(value)).expanduser()
    if not path.is_absolute():
        path = story_path.parent / path
    path = path.resolve()
    if not path.exists():
        raise FileNotFoundError(path)
    return path


def resolve_motion_prompt(page: dict[str, Any]) -> str:
    return str(
        page.get("motionPrompt")
        or page.get("motion_prompt")
        or page.get("prompt")
        or page.get("text")
        or ""
    ).strip()


def write_dry_run_manifest(pages: list[Any], workdir: Path, args: argparse.Namespace) -> None:
    requests: list[dict[str, Any]] = []
    for index, page in enumerate(pages, start=1):
        if not isinstance(page, dict):
            continue
        requests.append(
            {
                "page": page.get("page") or index,
                "image": page.get("image") or page.get("imageUrl") or page.get("image_url"),
                "uploadImage": str(
                    prepare_upload_image(
                        resolve_image_path(page, Path(".")),
                        workdir / "upload-images",
                        int(page.get("page") or index),
                        args.max_upload_dimension,
                        args.upload_aspect_ratio,
                    )
                )
                if page.get("image") or page.get("imageUrl") or page.get("image_url")
                else None,
                "prompt": resolve_motion_prompt(page),
                "model": args.model,
                "fallbackModel": args.fallback_model,
                "width": args.width,
                "height": args.height,
                "duration": args.duration,
                "mode": args.mode,
                "public": args.public,
                "style_ids": args.style_ids,
                "prompt_enhance": args.prompt_enhance,
            }
        )
    write_json(workdir / "dry-run-manifest.json", {"requests": requests})


def prepare_upload_image(
    image_path: Path,
    upload_dir: Path,
    page_no: int,
    max_dimension: int,
    aspect_ratio: str,
) -> Path:
    if max_dimension <= 0:
        return image_path

    with Image.open(image_path) as image:
        image = image.convert("RGB")
        ratio = parse_aspect_ratio(aspect_ratio)
        if ratio:
            target_width = max_dimension
            target_height = round(max_dimension / ratio)
            if target_height > max_dimension:
                target_height = max_dimension
                target_width = round(max_dimension * ratio)
            resized = ImageOps.fit(image, (target_width, target_height), method=Image.Resampling.LANCZOS, centering=(0.5, 0.5))
            upload_dir.mkdir(parents=True, exist_ok=True)
            target = upload_dir / f"page-{page_no:03d}-{target_width}x{target_height}.jpg"
            resized.save(target, format="JPEG", quality=94, optimize=True)
            return target

        width, height = image.size
        largest = max(width, height)
        if largest <= max_dimension:
            return image_path

        scale = max_dimension / largest
        target_size = (max(1, round(width * scale)), max(1, round(height * scale)))
        resized = image.resize(target_size, Image.Resampling.LANCZOS)

        upload_dir.mkdir(parents=True, exist_ok=True)
        target = upload_dir / f"page-{page_no:03d}-{target_size[0]}x{target_size[1]}.jpg"
        resized.save(target, format="JPEG", quality=94, optimize=True)
        return target


def parse_aspect_ratio(value: str) -> float | None:
    normalized = (value or "").strip().lower()
    if not normalized or normalized == "original":
        return None
    if ":" in normalized:
        left, right = normalized.split(":", 1)
        return float(left) / float(right)
    return float(normalized)


def concat_videos(ffmpeg: str, clips: list[Path], out_path: Path, concat_file: Path) -> None:
    concat_file.write_text(
        "".join(f"file '{clip.as_posix()}'\n" for clip in clips),
        encoding="utf-8",
    )
    command = [
        ffmpeg,
        "-y",
        "-f",
        "concat",
        "-safe",
        "0",
        "-i",
        str(concat_file),
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
        str(out_path),
    ]
    run(command)


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def run(command: list[str]) -> None:
    completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False)
    if completed.returncode != 0:
        sys.stderr.write(completed.stderr)
        raise RuntimeError(f"Command failed: {' '.join(command)}")


if __name__ == "__main__":
    raise SystemExit(main())
