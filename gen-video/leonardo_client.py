from __future__ import annotations

import os
import time
from pathlib import Path
from typing import Any

import requests
from dotenv import load_dotenv


def load_local_env() -> None:
    load_dotenv(Path(__file__).with_name(".env"))
    load_dotenv()


class LeonardoClient:
    def __init__(self, api_key: str | None = None, base_url: str | None = None) -> None:
        load_local_env()
        self.api_key = api_key or os.getenv("LEONARDO_API_KEY", "")
        if not self.api_key:
            raise ValueError("LEONARDO_API_KEY is required.")
        self.base_url = (base_url or os.getenv("LEONARDO_API_BASE_URL") or "https://cloud.leonardo.ai/api/rest").rstrip("/")
        self.session = requests.Session()
        self.session.headers.update(
            {
                "Authorization": f"Bearer {self.api_key}",
                "Accept": "application/json",
                "Content-Type": "application/json",
            }
        )

    def init_image_upload(self, image_path: str | Path) -> dict[str, Any]:
        path = Path(image_path)
        extension = path.suffix.lower().lstrip(".")
        if extension == "jpg":
            extension = "jpeg"
        if extension not in {"png", "jpeg", "webp"}:
            raise ValueError(f"Unsupported Leonardo upload extension: {path.suffix}")

        response = self.session.post(f"{self.base_url}/v1/init-image", json={"extension": extension}, timeout=60)
        response.raise_for_status()
        return response.json()

    def upload_image(self, image_path: str | Path) -> str:
        path = Path(image_path)
        init_payload = self.init_image_upload(path)
        upload_info = init_payload.get("uploadInitImage") or init_payload
        image_id = upload_info.get("id")
        upload_url = upload_info.get("url")
        fields = upload_info.get("fields") or {}
        if isinstance(fields, str):
            import json

            fields = json.loads(fields)
        if not image_id or not upload_url:
            raise RuntimeError(f"Unexpected init-image response: {init_payload}")

        with path.open("rb") as handle:
            upload_response = requests.post(
                upload_url,
                data=fields,
                files={"file": (path.name, handle)},
                timeout=120,
            )
        upload_response.raise_for_status()
        return str(image_id)

    def create_ltx_image_to_video(
        self,
        *,
        image_id: str,
        prompt: str,
        duration: int | None = None,
        width: int | None = None,
        height: int | None = None,
        model: str | None = None,
        generate_audio: bool | None = None,
    ) -> dict[str, Any]:
        payload = {
            "model": model or os.getenv("LEONARDO_VIDEO_MODEL", "ltxv-2.3-fast"),
            "prompt": prompt,
            "duration": duration or int(os.getenv("LEONARDO_VIDEO_DURATION", "8")),
            "width": width or int(os.getenv("LEONARDO_VIDEO_WIDTH", "1280")),
            "height": height or int(os.getenv("LEONARDO_VIDEO_HEIGHT", "720")),
            "generateAudio": self._resolve_bool(generate_audio, os.getenv("LEONARDO_GENERATE_AUDIO", "false")),
            "guidances": {
                "start_frame": {
                    "image_id": image_id,
                }
            },
        }
        response = self.session.post(f"{self.base_url}/v2/generations", json=payload, timeout=60)
        response.raise_for_status()
        return response.json()

    def create_hailuo_image_to_video(
        self,
        *,
        image_id: str,
        prompt: str,
        duration: int | None = None,
        width: int | None = None,
        height: int | None = None,
        model: str | None = None,
        mode: str | None = None,
        style_ids: list[str] | None = None,
        public: bool | None = None,
        prompt_enhance: str | None = None,
    ) -> dict[str, Any]:
        resolved_model = model or os.getenv("LEONARDO_VIDEO_MODEL", "hailuo-2_3-fast")
        resolved_mode = mode or os.getenv("LEONARDO_VIDEO_MODE", "RESOLUTION_768")
        resolved_duration = duration or int(os.getenv("LEONARDO_VIDEO_DURATION", "6"))
        resolved_width = width if width is not None else int(os.getenv("LEONARDO_VIDEO_WIDTH", "0"))
        resolved_height = height if height is not None else int(os.getenv("LEONARDO_VIDEO_HEIGHT", "0"))
        resolved_public = self._resolve_bool(public, os.getenv("LEONARDO_PUBLIC", "false"))
        resolved_style_ids = style_ids if style_ids is not None else self._parse_style_ids(os.getenv("LEONARDO_STYLE_IDS", ""))

        parameters: dict[str, Any] = {
            "prompt": prompt,
            "mode": resolved_mode,
            "duration": resolved_duration,
            "width": resolved_width,
            "height": resolved_height,
            "start_frame": {
                "id": image_id,
                "type": "UPLOADED",
            },
        }
        if resolved_style_ids:
            parameters["style_ids"] = resolved_style_ids
        if prompt_enhance:
            parameters["prompt_enhance"] = prompt_enhance

        payload = {
            "model": resolved_model,
            "public": resolved_public,
            "parameters": parameters,
        }
        response = self.session.post(f"{self.base_url}/v2/generations", json=payload, timeout=60)
        response.raise_for_status()
        payload = response.json()
        self._raise_api_errors(payload)
        return payload

    def get_generation(self, generation_id: str) -> dict[str, Any]:
        response = self.session.get(f"{self.base_url}/v2/generations/{generation_id}", timeout=60)
        if response.status_code == 404:
            response = self.session.get(f"{self.base_url}/v1/generations/{generation_id}", timeout=60)
        response.raise_for_status()
        return response.json()

    def wait_for_video_url(self, generation_id: str, *, timeout_seconds: int = 600, poll_seconds: int = 8) -> str:
        deadline = time.monotonic() + timeout_seconds
        last_payload: dict[str, Any] | None = None
        while time.monotonic() < deadline:
            payload = self.get_generation(generation_id)
            last_payload = payload
            video_url = self._extract_video_url(payload)
            if video_url:
                return video_url
            generation_by_pk = payload.get("generations_by_pk") if isinstance(payload, dict) else None
            status = str(
                payload.get("status")
                or payload.get("generation", {}).get("status")
                or (generation_by_pk.get("status") if isinstance(generation_by_pk, dict) else "")
                or ""
            ).upper()
            if status in {"FAILED", "ERROR"}:
                raise RuntimeError(f"Leonardo generation failed: {payload}")
            time.sleep(poll_seconds)
        raise TimeoutError(f"Timed out waiting for Leonardo video. Last payload: {last_payload}")

    def download(self, url: str, output_path: str | Path) -> Path:
        path = Path(output_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        downloader = requests if "cdn.leonardo.ai" in url else self.session
        with downloader.get(url, stream=True, timeout=300) as response:
            response.raise_for_status()
            with path.open("wb") as handle:
                for chunk in response.iter_content(chunk_size=1024 * 1024):
                    if chunk:
                        handle.write(chunk)
        return path

    @classmethod
    def extract_generation_id(cls, payload: dict[str, Any] | list[Any]) -> str:
        cls._raise_api_errors(payload)
        if isinstance(payload, list):
            for item in payload:
                if isinstance(item, dict):
                    try:
                        return cls.extract_generation_id(item)
                    except ValueError:
                        pass
            raise ValueError(f"Could not find generation id in Leonardo response: {payload}")

        direct_keys = (
            "generationId",
            "generation_id",
            "id",
            "jobId",
            "job_id",
        )
        for key in direct_keys:
            value = payload.get(key)
            if isinstance(value, str) and value:
                return value

        nested_candidates = (
            payload.get("generate"),
            payload.get("sdGenerationJob"),
            payload.get("generation"),
            payload.get("job"),
            payload.get("data"),
        )
        for candidate in nested_candidates:
            if isinstance(candidate, dict):
                try:
                    return cls.extract_generation_id(candidate)
                except ValueError:
                    pass

        raise ValueError(f"Could not find generation id in Leonardo response: {payload}")

    @staticmethod
    def _resolve_bool(value: bool | None, fallback: str) -> bool:
        if value is not None:
            return value
        return fallback.strip().lower() in {"1", "true", "yes", "on"}

    @staticmethod
    def _parse_style_ids(value: str) -> list[str]:
        return [item.strip() for item in value.split(",") if item.strip()]

    @staticmethod
    def _raise_api_errors(payload: Any) -> None:
        error_items: list[Any] = []
        if isinstance(payload, list):
            error_items = [item for item in payload if isinstance(item, dict) and ("message" in item or "extensions" in item)]
        elif isinstance(payload, dict) and isinstance(payload.get("errors"), list):
            error_items = payload["errors"]
        if error_items:
            raise RuntimeError(f"Leonardo API returned errors: {error_items}")

    @staticmethod
    def _extract_video_url(payload: dict[str, Any]) -> str | None:
        candidates = [
            payload.get("motionMP4URL"),
            payload.get("motionMp4Url"),
            payload.get("mp4Url"),
            payload.get("mp4_url"),
            payload.get("videoUrl"),
            payload.get("video_url"),
            payload.get("generation", {}).get("motionMP4URL") if isinstance(payload.get("generation"), dict) else None,
            payload.get("generation", {}).get("motionMp4Url") if isinstance(payload.get("generation"), dict) else None,
            payload.get("generation", {}).get("mp4Url") if isinstance(payload.get("generation"), dict) else None,
            payload.get("generation", {}).get("videoUrl") if isinstance(payload.get("generation"), dict) else None,
            payload.get("generations_by_pk", {}).get("motionMP4URL") if isinstance(payload.get("generations_by_pk"), dict) else None,
            payload.get("generations_by_pk", {}).get("motionMp4Url") if isinstance(payload.get("generations_by_pk"), dict) else None,
            payload.get("generations_by_pk", {}).get("mp4Url") if isinstance(payload.get("generations_by_pk"), dict) else None,
            payload.get("generations_by_pk", {}).get("videoUrl") if isinstance(payload.get("generations_by_pk"), dict) else None,
            payload.get("url"),
        ]
        for candidate in candidates:
            if isinstance(candidate, str) and candidate.startswith(("http://", "https://")):
                return candidate

        generations_by_pk = payload.get("generations_by_pk") if isinstance(payload, dict) else None
        generations = payload.get("generations") or payload.get("generated_videos") or payload.get("videos")
        if not generations and isinstance(generations_by_pk, dict):
            generations = generations_by_pk.get("generated_images") or generations_by_pk.get("generated_videos") or generations_by_pk.get("videos")
        if isinstance(generations, list):
            for item in generations:
                if not isinstance(item, dict):
                    continue
                for key in ("motionMP4URL", "motionMp4Url", "mp4Url", "mp4_url", "videoUrl", "video_url", "url"):
                    candidate = item.get(key)
                    if isinstance(candidate, str) and candidate.startswith(("http://", "https://")):
                        return candidate
        return None
