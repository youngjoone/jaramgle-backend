#!/usr/bin/env python3
"""Collect Korea Heritage Service Open API data for history story seeds.

The KHS API is separate from data.go.kr standard APIs. It does not use the
public-data service key and returns XML from list/detail endpoints.
"""

from __future__ import annotations

import argparse
import csv
import re
import ssl
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = ROOT / "data" / "raw" / "csv" / "history-tour-story"
PROCESSED_DIR = ROOT / "data" / "processed" / "csv" / "history-tour-story"

LIST_ENDPOINT = "https://www.khs.go.kr/cha/SearchKindOpenapiList.do"
DETAIL_ENDPOINT = "https://www.khs.go.kr/cha/SearchKindOpenapiDt.do"

BUSAN_CITY_CODE = "21"
DEFAULT_PAGE_UNIT = 100
DEFAULT_LIST_PATH = RAW_DIR / "khs-heritage-list-busan.csv"
DEFAULT_DETAIL_PATH = RAW_DIR / "khs-heritage-detail-busan.csv"
DEFAULT_SEED_PATH = PROCESSED_DIR / "khs-heritage-story-seeds.csv"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="KHS heritage API collector")
    subparsers = parser.add_subparsers(dest="command", required=True)

    list_parser = subparsers.add_parser("download-list", help="Download heritage list rows")
    list_parser.add_argument("--city-code", default=BUSAN_CITY_CODE, help="KHS city code. Busan is 21.")
    list_parser.add_argument("--page-unit", type=int, default=DEFAULT_PAGE_UNIT)
    list_parser.add_argument("--max-pages", type=int, default=0, help="0 means all pages")
    list_parser.add_argument("--output", type=Path, default=DEFAULT_LIST_PATH)
    list_parser.add_argument("--insecure", action="store_true")

    detail_parser = subparsers.add_parser("download-details", help="Download detail rows for list CSV")
    detail_parser.add_argument("--input", type=Path, default=DEFAULT_LIST_PATH)
    detail_parser.add_argument("--output", type=Path, default=DEFAULT_DETAIL_PATH)
    detail_parser.add_argument("--limit", type=int, default=0, help="0 means all rows")
    detail_parser.add_argument("--sleep", type=float, default=0.0)
    detail_parser.add_argument("--insecure", action="store_true")

    seed_parser = subparsers.add_parser("backfill", help="Create story seed CSV from detail CSV")
    seed_parser.add_argument("--input", type=Path, default=DEFAULT_DETAIL_PATH)
    seed_parser.add_argument("--output", type=Path, default=DEFAULT_SEED_PATH)

    all_parser = subparsers.add_parser("all", help="Download list, details, and story seeds")
    all_parser.add_argument("--city-code", default=BUSAN_CITY_CODE)
    all_parser.add_argument("--page-unit", type=int, default=DEFAULT_PAGE_UNIT)
    all_parser.add_argument("--max-pages", type=int, default=0)
    all_parser.add_argument("--limit", type=int, default=0)
    all_parser.add_argument("--sleep", type=float, default=0.0)
    all_parser.add_argument("--insecure", action="store_true")

    args = parser.parse_args(argv)
    ensure_dirs()

    if args.command == "download-list":
        rows = download_list(args.city_code, args.page_unit, args.max_pages, args.insecure)
        write_csv(args.output, rows)
        print(f"downloaded KHS list: {len(rows)} rows -> {relative(args.output)}")
        return 0

    if args.command == "download-details":
        list_rows = read_csv(args.input)
        detail_rows = download_details(list_rows, args.limit, args.sleep, args.insecure)
        write_csv(args.output, detail_rows)
        print(f"downloaded KHS details: {len(detail_rows)} rows -> {relative(args.output)}")
        return 0

    if args.command == "backfill":
        detail_rows = read_csv(args.input)
        seed_rows = [normalize_story_seed(row) for row in detail_rows]
        write_csv(args.output, seed_rows)
        print(f"backfilled KHS story seeds: {len(seed_rows)} rows -> {relative(args.output)}")
        return 0

    if args.command == "all":
        list_rows = download_list(args.city_code, args.page_unit, args.max_pages, args.insecure)
        write_csv(DEFAULT_LIST_PATH, list_rows)
        print(f"downloaded KHS list: {len(list_rows)} rows -> {relative(DEFAULT_LIST_PATH)}")
        detail_rows = download_details(list_rows, args.limit, args.sleep, args.insecure)
        write_csv(DEFAULT_DETAIL_PATH, detail_rows)
        print(f"downloaded KHS details: {len(detail_rows)} rows -> {relative(DEFAULT_DETAIL_PATH)}")
        seed_rows = [normalize_story_seed(row) for row in detail_rows]
        write_csv(DEFAULT_SEED_PATH, seed_rows)
        print(f"backfilled KHS story seeds: {len(seed_rows)} rows -> {relative(DEFAULT_SEED_PATH)}")
        return 0

    return 1


def ensure_dirs() -> None:
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    PROCESSED_DIR.mkdir(parents=True, exist_ok=True)


def download_list(city_code: str, page_unit: int, max_pages: int, insecure: bool) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    page = 1
    total_count = None
    while True:
        params = {
            "pageUnit": page_unit,
            "pageIndex": page,
            "ccbaCncl": "N",
            "ccbaCtcd": city_code,
        }
        root = request_xml(LIST_ENDPOINT, params, insecure=insecure)
        page_rows = [xml_children_to_dict(item) for item in iter_xml(root, "item")]
        if total_count is None:
            total_count = parse_int(find_xml_text(root, "totalCnt"))

        if not page_rows:
            break
        rows.extend(page_rows)
        print(f"downloaded list page {page}: {len(page_rows)} rows", flush=True)

        if max_pages and page >= max_pages:
            break
        if total_count and len(rows) >= total_count:
            break
        if len(page_rows) < page_unit:
            break
        page += 1
    return rows


def download_details(
    list_rows: list[dict[str, str]],
    limit: int,
    sleep_seconds: float,
    insecure: bool,
) -> list[dict[str, str]]:
    rows = list_rows[:limit] if limit else list_rows
    detail_rows = []
    for index, row in enumerate(rows, start=1):
        params = {
            "ccbaKdcd": row["ccbaKdcd"],
            "ccbaAsno": row["ccbaAsno"],
            "ccbaCtcd": row["ccbaCtcd"],
        }
        root = request_xml(DETAIL_ENDPOINT, params, insecure=insecure)
        detail = extract_detail(root)
        detail_rows.append({**row, **detail})
        if index == 1 or index % 50 == 0 or index == len(rows):
            print(f"downloaded detail {index}/{len(rows)}", flush=True)
        if sleep_seconds > 0:
            time.sleep(sleep_seconds)
    return detail_rows


def request_xml(endpoint: str, params: dict[str, str | int], insecure: bool) -> ET.Element:
    url = endpoint + "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 BusanStoryBookAI/0.1"})
    with urllib.request.urlopen(request, timeout=60, context=ssl_context(insecure)) as response:
        text = response.read().decode("utf-8", errors="replace")
    try:
        return ET.fromstring(text)
    except ET.ParseError as error:
        raise SystemExit(f"KHS API did not return valid XML. Response starts with: {text[:120]}") from error


def ssl_context(insecure: bool) -> ssl.SSLContext | None:
    if not insecure:
        return None
    return ssl._create_unverified_context()


def extract_detail(root: ET.Element) -> dict[str, str]:
    root_fields = {}
    for child in list(root):
        if xml_local_name(child.tag) != "item":
            root_fields[xml_local_name(child.tag)] = normalize_text(child.text or "")

    item = next(iter_xml(root, "item"), None)
    if item is None:
        return root_fields
    return {**root_fields, **xml_children_to_dict(item)}


def normalize_story_seed(row: dict[str, str]) -> dict[str, str]:
    name = pick(row, "ccbaMnm1")
    category = join_nonempty(pick(row, "ccmaName"), pick(row, "gcodeName"), pick(row, "bcodeName"))
    region = join_nonempty(pick(row, "ccbaCtcdNm").strip(), pick(row, "ccsiName"))
    content = pick(row, "content")
    era = pick(row, "ccceName")
    address = pick(row, "ccbaLcad")
    return {
        "source_name": "국가유산청_국가유산 검색 Open API",
        "source_url": "https://www.khs.go.kr/cha/SearchKindOpenapiList.do",
        "reference_date": pick(row, "regDt"),
        "item_type": "national_heritage",
        "name_ko": name,
        "category": category or "국가유산",
        "region": region,
        "address": address,
        "latitude": pick(row, "latitude"),
        "longitude": pick(row, "longitude"),
        "summary": content or f"{name}은(는) 역사 탐방 동화의 배경으로 사용할 수 있다.",
        "story_use": "아이를 주인공으로 한 국가유산 역사 탐방 동화 배경",
        "mission_use": "유산의 이름, 시대, 위치, 이미지 속 특징을 찾아보는 관찰 미션",
        "age_tags": "6-8;9-11",
        "keywords": join_keywords("국가유산", name, category, era, region, pick(row, "ccbaAdmin")),
        **row,
    }


def xml_children_to_dict(element: ET.Element) -> dict[str, str]:
    row = {}
    for child in list(element):
        row[xml_local_name(child.tag)] = normalize_text(child.text or "")
    return row


def find_xml_text(root: ET.Element, name: str) -> str:
    for element in root.iter():
        if xml_local_name(element.tag) == name:
            return normalize_text(element.text or "")
    return ""


def iter_xml(root: ET.Element, name: str) -> Iterable[ET.Element]:
    for element in root.iter():
        if xml_local_name(element.tag) == name:
            yield element


def xml_local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def normalize_text(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file)
        return [{key: value.strip() for key, value in row.items() if key is not None} for row in reader]


def write_csv(path: Path, rows: list[dict[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = collect_fields(rows)
    with path.open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def collect_fields(rows: list[dict[str, str]]) -> list[str]:
    preferred = [
        "source_name",
        "source_url",
        "reference_date",
        "item_type",
        "name_ko",
        "category",
        "region",
        "address",
        "latitude",
        "longitude",
        "summary",
        "story_use",
        "mission_use",
        "age_tags",
        "keywords",
    ]
    seen = set()
    fields = []
    for field in preferred:
        if any(field in row for row in rows):
            fields.append(field)
            seen.add(field)
    for row in rows:
        for field in row:
            if field not in seen:
                fields.append(field)
                seen.add(field)
    return fields or preferred


def pick(row: dict[str, str], key: str) -> str:
    return row.get(key, "").strip()


def join_nonempty(*values: str) -> str:
    return "; ".join(value.strip() for value in values if value and value.strip())


def join_keywords(*values: str) -> str:
    words = []
    for value in values:
        for word in re.split(r"[\s,;/·]+", value or ""):
            word = word.strip()
            if word and word not in words:
                words.append(word)
    return ";".join(words)


def parse_int(value: str) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def relative(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(ROOT))
    except ValueError:
        return str(path)


if __name__ == "__main__":
    sys.exit(main())
