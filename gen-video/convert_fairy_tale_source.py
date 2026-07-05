#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


# BGM mood → filename mapping (files live in gen-video/bgm/)
BGM_MOODS = {
    "magical",
    "warm",
    "adventure",
    "peaceful",
    "cheerful",
    "mysterious",
    "dreamy",
}
BGM_DEFAULT = "magical"


def resolve_bgm(mood: str | None) -> str:
    """Return a validated BGM mood key, falling back to the default."""
    key = (mood or "").strip().lower()
    return key if key in BGM_MOODS else BGM_DEFAULT


def main() -> int:
    parser = argparse.ArgumentParser(description="Convert fairy_tale source JSON into story_video.py input JSON.")
    parser.add_argument("--source", required=True, help="Source fairy_tale JSON path.")
    parser.add_argument("--out", required=True, help="Output story JSON path.")
    parser.add_argument("--duration", type=float, default=8.0, help="Default duration per page when no audio exists.")
    parser.add_argument("--absolute", action="store_true", help="Write absolute image paths.")
    parser.add_argument("--bgm", help="Override story-level BGM mood (e.g. magical, warm, dreamy).")
    args = parser.parse_args()

    source_path = Path(args.source).expanduser().resolve()
    out_path = Path(args.out).expanduser().resolve()

    with source_path.open("r", encoding="utf-8") as handle:
        data = json.load(handle)

    fairy_tale = data.get("fairy_tale")
    if not isinstance(fairy_tale, dict):
        raise ValueError("Source JSON must contain fairy_tale object.")

    # BGM: CLI flag > source story-level > default
    story_bgm = resolve_bgm(args.bgm or fairy_tale.get("bgm"))

    source_dir = source_path.parent
    pages: list[dict[str, Any]] = []
    for index, chapter in enumerate(fairy_tale.get("chapters") or [], start=1):
        if not isinstance(chapter, dict):
            continue
        image_value = chapter.get("image") or f"{chapter.get('chapter_number') or index}.png"
        image_path = Path(str(image_value))
        if not image_path.is_absolute():
            image_path = source_dir / image_path
        if not image_path.exists():
            raise FileNotFoundError(f"Image for chapter {index} not found: {image_path}")

        motion = chapter.get("video_motion") if isinstance(chapter.get("video_motion"), dict) else {}
        # Per-chapter BGM falls back to story-level BGM
        chapter_bgm = resolve_bgm(chapter.get("bgm")) if chapter.get("bgm") else story_bgm
        page: dict[str, Any] = {
            "page": int(chapter.get("chapter_number") or index),
            "title": chapter.get("chapter_title") or f"Chapter {index}",
            "image": str(image_path.resolve() if args.absolute else image_path.relative_to(source_dir.parent)),
            "text": chapter.get("script") or "",
            "motionPrompt": motion.get("prompt_en") or "",
            "motionDescription": motion.get("description_kr") or "",
            "duration": args.duration,
            "bgm": chapter_bgm,
        }
        pages.append(page)

    output = {
        "title": fairy_tale.get("book_title") or "fairy tale",
        "bgm": story_bgm,
        "pages": pages,
    }

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    print(out_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
