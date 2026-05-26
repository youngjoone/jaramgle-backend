#!/usr/bin/env python3
"""Download and backfill public-data CSV files for Busan StoryBook AI.

This module intentionally uses only the Python standard library so it can run
before the project has a full Python environment.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import ssl
import sys
import xml.etree.ElementTree as ET
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[2]
DATASET_CONFIG = ROOT / "data" / "public-datasets.json"


@dataclass(frozen=True)
class Dataset:
    id: str
    title: str
    status: str
    module: str
    source_url: str
    download_url: str
    api: dict[str, Any]
    raw_path: Path
    processed_path: Path
    normalize_type: str
    busan_filter_columns: tuple[str, ...]
    required_columns: tuple[str, ...]
    manual_template_columns: tuple[str, ...]
    extra_filters: tuple[dict[str, str], ...]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Public data download/backfill pipeline")
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("list", help="List configured datasets")
    subparsers.add_parser("init", help="Create configured data directories")

    download_parser = subparsers.add_parser("download", help="Download one dataset when a direct URL exists")
    download_parser.add_argument("dataset_id")
    download_parser.add_argument("--insecure", action="store_true", help="Disable SSL verification for local certificate-chain issues")

    template_parser = subparsers.add_parser("template", help="Create a manual CSV template")
    template_parser.add_argument("dataset_id")
    template_parser.add_argument("--force", action="store_true")

    backfill_parser = subparsers.add_parser("backfill", help="Normalize one raw CSV")
    backfill_parser.add_argument("dataset_id")
    backfill_parser.add_argument("--busan-only", action="store_true", help="Apply Busan filtering only when explicitly needed")

    backfill_all_parser = subparsers.add_parser("backfill-all", help="Backfill every dataset with an existing raw CSV")
    backfill_all_parser.add_argument("--busan-only", action="store_true", help="Apply Busan filtering only when explicitly needed")

    args = parser.parse_args(argv)
    datasets = load_datasets()

    if args.command == "list":
        list_datasets(datasets)
        return 0

    if args.command == "init":
        init_workspace(datasets)
        return 0

    if args.command == "download":
        dataset = require_dataset(datasets, args.dataset_id)
        download_dataset(dataset, insecure=args.insecure)
        return 0

    if args.command == "template":
        dataset = require_dataset(datasets, args.dataset_id)
        create_template(dataset, force=args.force)
        return 0

    if args.command == "backfill":
        dataset = require_dataset(datasets, args.dataset_id)
        backfill_dataset(dataset, busan_only=args.busan_only)
        return 0

    if args.command == "backfill-all":
        for dataset in datasets:
            if dataset.raw_path.exists():
                backfill_dataset(dataset, busan_only=args.busan_only)
            else:
                print(f"skip {dataset.id}: raw file missing at {relative(dataset.raw_path)}")
        return 0

    return 1


def load_datasets() -> list[Dataset]:
    payload = json.loads(DATASET_CONFIG.read_text(encoding="utf-8"))
    datasets = []
    for item in payload["datasets"]:
        datasets.append(
            Dataset(
                id=item["id"],
                title=item["title"],
                status=item["status"],
                module=item["module"],
                source_url=item.get("source_url", ""),
                download_url=item.get("download_url", ""),
                api=item.get("api", {}),
                raw_path=ROOT / item["raw_path"],
                processed_path=ROOT / item["processed_path"],
                normalize_type=item["normalize_type"],
                busan_filter_columns=tuple(item.get("busan_filter_columns", [])),
                required_columns=tuple(item.get("required_columns", [])),
                manual_template_columns=tuple(item.get("manual_template_columns", [])),
                extra_filters=tuple(item.get("extra_filters", [])),
            )
        )
    return datasets


def require_dataset(datasets: Iterable[Dataset], dataset_id: str) -> Dataset:
    for dataset in datasets:
        if dataset.id == dataset_id:
            return dataset
    raise SystemExit(f"Unknown dataset: {dataset_id}")


def list_datasets(datasets: Iterable[Dataset]) -> None:
    for dataset in datasets:
        raw_exists = "raw-ok" if dataset.raw_path.exists() else "raw-missing"
        print(f"{dataset.id:24} {dataset.status:16} {raw_exists:12} {dataset.title}")


def init_workspace(datasets: Iterable[Dataset]) -> None:
    for dataset in datasets:
        dataset.raw_path.parent.mkdir(parents=True, exist_ok=True)
        dataset.processed_path.parent.mkdir(parents=True, exist_ok=True)
    print("initialized data directories")


def download_dataset(dataset: Dataset, insecure: bool = False) -> None:
    dataset.raw_path.parent.mkdir(parents=True, exist_ok=True)
    if dataset.api:
        download_api_dataset(dataset, insecure=insecure)
        return

    if not dataset.download_url:
        raise SystemExit(
            f"{dataset.id} has no direct download_url. Open {dataset.source_url or 'source page'} "
            f"and save CSV to {relative(dataset.raw_path)}"
        )

    request = urllib.request.Request(
        dataset.download_url,
        headers={"User-Agent": "Mozilla/5.0 BusanStoryBookAI/0.1"},
    )
    with urllib.request.urlopen(request, timeout=60, context=ssl_context(insecure)) as response:
        dataset.raw_path.write_bytes(response.read())
    print(f"downloaded {dataset.id} -> {relative(dataset.raw_path)}")


def download_api_dataset(dataset: Dataset, insecure: bool = False) -> None:
    api = dataset.api
    endpoint = api["endpoint"]
    key_env = api.get("key_env", "DATA_GO_KR_API_KEY")
    api_key = os.environ.get(key_env) or api.get("default_key")
    if not api_key:
        raise SystemExit(
            f"{dataset.id} requires {key_env}. "
            f"Example: {key_env}=... python3 -m scripts.data.public_data_pipeline download {dataset.id}"
        )

    page_param = api.get("page_param", "pageNo")
    rows_param = api.get("rows_param", "numOfRows")
    rows_per_page = int(api.get("rows_per_page", 1000))
    page = int(api.get("initial_page", 1))
    max_pages = int(api.get("max_pages", 0))
    all_rows: list[dict[str, Any]] = []
    pages_downloaded = 0

    while True:
        params = {
            "serviceKey": api_key,
            page_param: page,
            rows_param: rows_per_page,
        }
        if api.get("format_param") and api.get("format"):
            params[api["format_param"]] = api["format"]

        url = endpoint + "?" + urllib.parse.urlencode(params)
        try:
            if api.get("response_format") == "xml":
                items, total_count = extract_xml_api_items(request_text(url, insecure=insecure))
            else:
                items, total_count = extract_api_items(request_json(url, insecure=insecure))
        except SystemExit as error:
            if all_rows:
                print(f"warning: stopped after {len(all_rows)} rows because the API returned: {error}")
                break
            raise
        if not items:
            break

        all_rows.extend(flatten_record(item) for item in items)
        pages_downloaded += 1
        print(f"downloaded page {page}: {len(items)} rows", flush=True)

        if max_pages and pages_downloaded >= max_pages:
            print(f"stopped at configured max_pages={max_pages}", flush=True)
            break
        if total_count and len(all_rows) >= total_count:
            break
        if len(items) < rows_per_page:
            break
        page += 1

    if not all_rows:
        raise SystemExit(f"{dataset.id} returned no rows.")

    write_csv(dataset.raw_path, stringify_rows(all_rows))
    print(f"downloaded {dataset.id}: {len(all_rows)} rows -> {relative(dataset.raw_path)}")


def request_json(url: str, insecure: bool = False) -> dict[str, Any]:
    text = request_text(url, insecure=insecure)
    try:
        return json.loads(text)
    except json.JSONDecodeError as error:
        raise SystemExit(f"API did not return JSON. Check the key or endpoint. Response starts with: {text[:120]}") from error


def request_text(url: str, insecure: bool = False) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 BusanStoryBookAI/0.1"})
    with urllib.request.urlopen(request, timeout=60, context=ssl_context(insecure)) as response:
        return response.read().decode("utf-8")


def ssl_context(insecure: bool) -> ssl.SSLContext | None:
    if not insecure:
        return None
    return ssl._create_unverified_context()


def extract_api_items(payload: dict[str, Any]) -> tuple[list[dict[str, Any]], int | None]:
    if isinstance(payload.get("data"), list):
        total_count = None
        raw_total = payload.get("totalCount") or payload.get("total_count")
        if raw_total not in (None, ""):
            try:
                total_count = int(raw_total)
            except (TypeError, ValueError):
                total_count = None
        return payload["data"], total_count

    response = payload.get("response", {})
    header = response.get("header", {}) if isinstance(response, dict) else {}
    result_code = str(header.get("resultCode", "")).strip()
    result_msg = str(header.get("resultMsg", "")).strip()
    if result_code and result_code not in {"00", "0", "NORMAL_CODE"}:
        raise SystemExit(f"API error {result_code}: {result_msg or 'unknown error'}")

    body = response.get("body", payload.get("body", payload)) if isinstance(response, dict) else payload.get("body", payload)
    items = body.get("items", []) if isinstance(body, dict) else []
    if isinstance(items, dict):
        items = items.get("item", [])
    if isinstance(items, dict):
        items = [items]
    if not isinstance(items, list):
        items = []

    total_count = None
    if isinstance(body, dict):
        raw_total = body.get("totalCount") or body.get("total_count")
        if raw_total not in (None, ""):
            try:
                total_count = int(raw_total)
            except (TypeError, ValueError):
                total_count = None
    return items, total_count


def extract_xml_api_items(text: str) -> tuple[list[dict[str, Any]], int | None]:
    try:
        root = ET.fromstring(text)
    except ET.ParseError as error:
        raise SystemExit(f"API did not return valid XML. Response starts with: {text[:120]}") from error

    result_code = find_xml_text(root, "ResultCode") or find_xml_text(root, "resultCode")
    result_msg = find_xml_text(root, "ResultMsg") or find_xml_text(root, "resultMsg")
    if result_code and result_code.strip() not in {"00", "0", "NORMAL_CODE"}:
        raise SystemExit(f"API error {result_code}: {result_msg or 'unknown error'}")

    items = []
    for item in iter_xml(root, "item"):
        items.append(xml_children_to_dict(item))

    total_count = None
    raw_total = find_xml_text(root, "totalCount") or find_xml_text(root, "total_count")
    if raw_total not in (None, ""):
        try:
            total_count = int(raw_total)
        except (TypeError, ValueError):
            total_count = None
    return items, total_count


def xml_children_to_dict(element: ET.Element) -> dict[str, str]:
    row = {}
    for child in list(element):
        row[xml_local_name(child.tag)] = (child.text or "").strip()
    return row


def find_xml_text(root: ET.Element, name: str) -> str:
    for element in root.iter():
        if xml_local_name(element.tag) == name:
            return (element.text or "").strip()
    return ""


def iter_xml(root: ET.Element, name: str) -> Iterable[ET.Element]:
    for element in root.iter():
        if xml_local_name(element.tag) == name:
            yield element


def xml_local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def flatten_record(record: dict[str, Any]) -> dict[str, Any]:
    flattened = {}
    for key, value in record.items():
        if isinstance(value, (dict, list)):
            flattened[key] = json.dumps(value, ensure_ascii=False)
        else:
            flattened[key] = value
    return flattened


def stringify_rows(rows: list[dict[str, Any]]) -> list[dict[str, str]]:
    return [{key: "" if value is None else str(value) for key, value in row.items()} for row in rows]


def create_template(dataset: Dataset, force: bool = False) -> None:
    columns = dataset.manual_template_columns or dataset.required_columns
    if not columns:
        raise SystemExit(f"{dataset.id} has no template columns configured.")
    if dataset.raw_path.exists() and not force:
        raise SystemExit(f"template already exists: {relative(dataset.raw_path)}. Use --force to overwrite.")

    dataset.raw_path.parent.mkdir(parents=True, exist_ok=True)
    with dataset.raw_path.open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=list(columns))
        writer.writeheader()
    print(f"created template {relative(dataset.raw_path)}")


def backfill_dataset(dataset: Dataset, busan_only: bool = False) -> None:
    if not dataset.raw_path.exists():
        if dataset.manual_template_columns:
            create_template(dataset)
        raise SystemExit(f"raw file missing for {dataset.id}: {relative(dataset.raw_path)}")

    rows = read_csv(dataset.raw_path)
    if busan_only:
        rows = filter_busan(rows, dataset.busan_filter_columns)
    rows = apply_extra_filters(rows, dataset.extra_filters)
    normalized_rows = normalize_rows(dataset, rows)

    dataset.processed_path.parent.mkdir(parents=True, exist_ok=True)
    write_csv(dataset.processed_path, normalized_rows)
    print(f"backfilled {dataset.id}: {len(normalized_rows)} rows -> {relative(dataset.processed_path)}")


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file)
        return [{clean_key(key): value.strip() for key, value in row.items() if key is not None} for row in reader]


def write_csv(path: Path, rows: list[dict[str, str]]) -> None:
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


def filter_busan(rows: list[dict[str, str]], columns: tuple[str, ...]) -> list[dict[str, str]]:
    if not columns:
        return rows
    filtered = []
    for row in rows:
        haystack = " ".join(row.get(column, "") for column in columns)
        if "부산" in haystack or "Busan" in haystack:
            filtered.append(row)
    return filtered


def apply_extra_filters(rows: list[dict[str, str]], filters: tuple[dict[str, str], ...]) -> list[dict[str, str]]:
    filtered = rows
    for filter_spec in filters:
        contains = filter_spec["contains"]
        if "any_column" in filter_spec:
            columns = filter_spec["any_column"]
            filtered = [row for row in filtered if any(contains in row.get(column, "") for column in columns)]
        else:
            column = filter_spec["column"]
            filtered = [row for row in filtered if contains in row.get(column, "")]
    return filtered


def normalize_rows(dataset: Dataset, rows: list[dict[str, str]]) -> list[dict[str, str]]:
    normalizer = NORMALIZERS.get(dataset.normalize_type, normalize_generic)
    return [normalizer(dataset, row) for row in rows]


def normalize_generic(dataset: Dataset, row: dict[str, str]) -> dict[str, str]:
    return {
        "source_name": dataset.title,
        "source_url": dataset.source_url,
        "reference_date": pick(row, "데이터기준일자", "referenceDate", "REFERENCE_DATE"),
        "item_type": dataset.normalize_type,
        **row,
    }


def normalize_urban_parks(dataset: Dataset, row: dict[str, str]) -> dict[str, str]:
    name = pick(row, "공원명", "PARK_NM", "parkNm")
    category = pick(row, "공원구분", "PARK_SE", "parkSe")
    facilities = join_nonempty(
        pick(row, "공원보유시설(유희시설)", "AMSMT_FCLTY", "amsmtFclty"),
        pick(row, "공원보유시설(운동시설)", "MVM_FCLTY", "mvmFclty"),
        pick(row, "공원보유시설(교양시설)", "CLTR_FCLTY", "cltrFclty"),
    )
    return {
        "source_name": dataset.title,
        "source_url": dataset.source_url,
        "reference_date": pick(row, "데이터기준일자", "REFERENCE_DATE", "referenceDate"),
        "item_type": "place",
        "name_ko": name,
        "category": category or "도시공원",
        "region": infer_region(row),
        "address": pick(row, "소재지도로명주소", "RDNMADR", "rdnmadr", "소재지지번주소", "LNMADR", "lnmadr"),
        "latitude": pick(row, "위도", "LATITUDE", "latitude"),
        "longitude": pick(row, "경도", "LONGITUDE", "longitude"),
        "summary": f"{name}은(는) {category or '공원'}으로, 아이의 생활권 동화 배경으로 사용할 수 있다.",
        "story_use": "아이의 집 주변 산책·탐험 동화 배경",
        "mission_use": facilities or "공원에서 색깔, 나무, 놀이시설을 찾아보는 관찰 미션",
        "age_tags": "4-7;8-10",
        "keywords": join_keywords("공원", category, facilities),
        **row,
    }


def normalize_libraries(dataset: Dataset, row: dict[str, str]) -> dict[str, str]:
    name = pick(row, "도서관명", "LBRRY_NM", "lbrryNm")
    library_type = pick(row, "도서관유형", "LBRRY_SE", "lbrrySe")
    return {
        "source_name": dataset.title,
        "source_url": dataset.source_url,
        "reference_date": pick(row, "데이터기준일자", "REFERENCE_DATE", "referenceDate"),
        "item_type": "place",
        "name_ko": name,
        "category": library_type or "도서관",
        "region": pick(row, "시군구명", "SIGNGU_NM", "signguNm") or infer_region(row),
        "address": pick(row, "소재지도로명주소", "RDNMADR", "rdnmadr"),
        "latitude": pick(row, "위도", "LATITUDE", "latitude"),
        "longitude": pick(row, "경도", "LONGITUDE", "longitude"),
        "summary": f"{name}은(는) 아이의 독서 습관과 지역 탐험을 연결하는 동화 배경으로 사용할 수 있다.",
        "story_use": "도서관에서 책 속 주인공을 만나는 생활권 독서 동화",
        "mission_use": "책 표지 색깔 찾기, 사서에게 추천 도서 묻기, 가족 독서 기록 남기기",
        "age_tags": "4-7;8-10",
        "keywords": join_keywords("도서관", library_type, pick(row, "자료수(도서)", "BOOK_CO", "bookCo")),
        **row,
    }


def normalize_museum_art(dataset: Dataset, row: dict[str, str]) -> dict[str, str]:
    name = pick(row, "시설명", "fcltyNm")
    intro = pick(row, "박물관미술관소개", "fcltyIntrcn")
    return {
        "source_name": dataset.title,
        "source_url": dataset.source_url,
        "reference_date": pick(row, "데이터기준일자", "referenceDate"),
        "item_type": "place",
        "name_ko": name,
        "category": pick(row, "박물관미술관구분", "fcltyType") or "박물관·미술관",
        "region": infer_region(row),
        "address": pick(row, "소재지도로명주소", "rdnmadr", "소재지지번주소", "lnmadr"),
        "latitude": pick(row, "위도", "latitude"),
        "longitude": pick(row, "경도", "longitude"),
        "summary": intro or f"{name}을(를) 배경으로 관람 전후 동화를 만들 수 있다.",
        "story_use": "관람 전 예습 동화와 방문 후 기념 동화책 배경",
        "mission_use": "전시실에서 마음에 드는 작품이나 물건을 찾아 가족과 이야기하기",
        "age_tags": "5-7;8-10",
        "keywords": join_keywords("박물관", "미술관", name, intro),
        **row,
    }


def normalize_marine_life_collection(dataset: Dataset, row: dict[str, str]) -> dict[str, str]:
    korean_name = pick(row, "국명")
    scientific_name = pick(row, "학명")
    sea_area = pick(row, "해역")
    region = pick(row, "지역", "국가")
    category = pick(row, "분류체계") or "해양생물"
    name = korean_name or scientific_name
    return {
        "source_name": dataset.title,
        "source_url": dataset.source_url,
        "reference_date": pick(row, "채집일"),
        "item_type": "marine_life",
        "name_ko": name,
        "category": category,
        "region": region,
        "address": join_nonempty(pick(row, "국가"), sea_area, region),
        "latitude": pick(row, "위도"),
        "longitude": pick(row, "경도"),
        "summary": f"{name}은(는) {sea_area or region or '바다'}에서 관찰·채집된 해양생물 소재로 사용할 수 있다.",
        "story_use": "해양생물 캐릭터와 바다 탐험 동화 소재",
        "mission_use": "생물의 이름, 서식 해역, 분류를 활용한 관찰 질문과 퀴즈",
        "age_tags": "5-7;8-10",
        "keywords": join_keywords("해양생물", korean_name, scientific_name, category, sea_area, region),
        **row,
    }


def normalize_marine_life_species(dataset: Dataset, row: dict[str, str]) -> dict[str, str]:
    korean_name = pick(row, "CommKorNm")
    scientific_name = pick(row, "SpcScitfNmShort", "SpcScitfNm", "CorrSpcScitfNm")
    family = pick(row, "FamilyKR", "Family")
    habitat = pick(row, "HABI")
    domestic_distribution = pick(row, "NADI")
    summary = pick(row, "ABST", "FORM")
    name = korean_name or scientific_name
    category = join_nonempty(
        pick(row, "KingdomKR", "Kingdom"),
        pick(row, "PhylumDivisionKR", "PhylumDivision"),
        pick(row, "ClassKR", "Class"),
        pick(row, "OrderKR", "Order"),
        family,
    )
    return {
        "source_name": dataset.title,
        "source_url": dataset.source_url,
        "reference_date": "",
        "item_type": "marine_life_species",
        "name_ko": name,
        "category": category or "해양생물종",
        "region": domestic_distribution,
        "address": join_nonempty(domestic_distribution, habitat),
        "latitude": "",
        "longitude": "",
        "summary": summary or f"{name}은(는) 해양생물 캐릭터와 과학 동화 소재로 사용할 수 있다.",
        "story_use": "해양생물의 생김새, 서식지, 분포를 반영한 과학 동화 소재",
        "mission_use": "국명·학명·서식지·분류체계를 활용한 관찰 질문과 퀴즈",
        "age_tags": "5-7;8-10",
        "keywords": join_keywords("해양생물종", korean_name, scientific_name, family, habitat, domestic_distribution),
        **row,
    }


def normalize_tourist_attractions(dataset: Dataset, row: dict[str, str]) -> dict[str, str]:
    name = pick(row, "관광지명", "trrsrtNm", "향토문화유적명")
    intro = pick(row, "관광지소개", "trrsrtIntrcn", "향토문화유적소개")
    return {
        "source_name": dataset.title,
        "source_url": dataset.source_url,
        "reference_date": pick(row, "데이터기준일자", "referenceDate"),
        "item_type": "place",
        "name_ko": name,
        "category": pick(row, "관광지구분", "trrsrtSe", "향토문화유적구분") or "관광지",
        "region": infer_region(row),
        "address": pick(row, "소재지도로명주소", "rdnmadr", "소재지지번주소", "lnmadr"),
        "latitude": pick(row, "위도", "latitude"),
        "longitude": pick(row, "경도", "longitude"),
        "summary": intro or f"{name}을(를) 배경으로 부산 탐방 동화를 만들 수 있다.",
        "story_use": "지역 역사·관광 탐방 동화 배경",
        "mission_use": "장소의 특징을 찾아보고 가족 여행 기록 남기기",
        "age_tags": "5-7;8-10",
        "keywords": join_keywords("관광", pick(row, "관광지구분", "trrsrtSe"), name, intro),
        **row,
    }


def normalize_local_heritage(dataset: Dataset, row: dict[str, str]) -> dict[str, str]:
    name = pick(row, "향토문화유적명", "relicsNm")
    heritage_type = pick(row, "향토문화유적구분", "relicsKnd")
    heritage_kind = pick(row, "향토문화유적종류", "relicsSe")
    period = pick(row, "조성시대", "makePd")
    intro = pick(row, "향토문화유적소개", "relicsIntrcn")
    category = join_nonempty(heritage_type, heritage_kind) or "향토문화유적"
    return {
        "source_name": dataset.title,
        "source_url": dataset.source_url,
        "reference_date": pick(row, "데이터기준일자", "referenceDate"),
        "item_type": "heritage",
        "name_ko": name,
        "category": category,
        "region": infer_region(row),
        "address": pick(row, "소재지도로명주소", "rdnmadr", "소재지지번주소", "lnmadr"),
        "latitude": pick(row, "위도", "latitude"),
        "longitude": pick(row, "경도", "longitude"),
        "summary": intro or f"{name}은(는) 지역 역사 탐방 동화의 배경으로 사용할 수 있다.",
        "story_use": "아이를 주인공으로 한 동네 역사·문화유적 탐방 동화 배경",
        "mission_use": "유적의 이름, 시대, 모양, 관리기관을 찾아보는 관찰 미션",
        "age_tags": "6-8;9-11",
        "keywords": join_keywords("향토문화유적", name, heritage_type, heritage_kind, period, intro),
        **row,
    }


NORMALIZERS = {
    "urban_parks": normalize_urban_parks,
    "libraries": normalize_libraries,
    "museum_art": normalize_museum_art,
    "marine_life_collection": normalize_marine_life_collection,
    "marine_life_species": normalize_marine_life_species,
    "tourist_attractions": normalize_tourist_attractions,
    "local_heritage": normalize_local_heritage,
}


def pick(row: dict[str, str], *keys: str) -> str:
    for key in keys:
        value = row.get(key)
        if value:
            return value
    return ""


def clean_key(key: str) -> str:
    return key.strip().replace("\ufeff", "")


def infer_region(row: dict[str, str]) -> str:
    text = pick(row, "시군구명", "SIGNGU_NM", "signguNm", "시군구")
    if text:
        return text
    address = pick(row, "소재지도로명주소", "RDNMADR", "rdnmadr", "소재지지번주소", "LNMADR", "lnmadr", "주소")
    parts = address.split()
    if len(parts) >= 2:
        return f"{parts[0]} {parts[1]}"
    if parts:
        return parts[0]
    return ""


def join_nonempty(*values: str) -> str:
    return "; ".join(value for value in values if value)


def join_keywords(*values: str) -> str:
    words = []
    for value in values:
        if not value:
            continue
        for token in str(value).replace(",", " ").replace(";", " ").split():
            token = token.strip()
            if token and token not in words:
                words.append(token)
    return ";".join(words[:12])


def relative(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("aborted", file=sys.stderr)
        raise SystemExit(130)
