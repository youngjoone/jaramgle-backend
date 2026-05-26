# Data

부산 특화 AI 동화책 생성 플랫폼에 필요한 공공데이터 후보를 CSV로 확보하고, 어떤 데이터가 어떤 서비스 화면·상품·사업화 모듈로 바뀌는지 검토한다.

아직 DB 적재나 고정 스키마를 전제로 하지 않는다. 이 디렉토리의 1차 목적은 **CSV 원본 확보, 출처 기록, 데이터 활용성 비교**다.

## 제안 구조

```text
data/
  raw/
    csv/
      busan-storybook/
      neighborhood-story/
      history-tour-story/
  processed/
    csv/
    json/
  samples/
    public-data/
    demo-child-profile.json
    demo-story-result.json
```

## 수집 명세

실제 공공데이터 후보, 공식 경로, 확인된 컬럼, 저장 파일명은 [public-data-catalog.md](./public-data-catalog.md)에 정리한다.

상태값은 아래처럼 관리한다.

| 상태 | 의미 |
| --- | --- |
| `confirmed` | 공식 경로와 주요 컬럼을 확인했다. |
| `api-to-csv` | API형 데이터라 스크립트로 CSV 변환이 필요하다. |
| `manual-or-scrape` | 공식 페이지는 있으나 CSV 제공 여부가 불명확해 수동 정리 또는 스크래핑이 필요하다. |
| `tbd` | 데이터 후보만 있고, 공식 경로·컬럼 확인이 필요하다. |

## CSV 확보 원칙

- 원본은 `data/raw/csv` 아래에 그대로 둔다.
- 파일명에는 데이터명, 지역, 기준일을 최대한 넣는다.
- 데이터 출처와 컬럼 설명은 `data/public-data-catalog.md` 하나에 모아 관리한다.
- 1차 MVP는 데이터를 많이 모으는 것보다, 화면과 생성 결과에 바로 쓰이는 데이터를 우선 확보한다.
- API만 제공되는 데이터는 우선 샘플 응답을 CSV로 변환해서 비교한다.

## 서비스 기준 데이터 레이어

| 레이어 | 확보할 CSV 후보 | 서비스로 바뀌는 화면·상품 |
| --- | --- | --- |
| 생활권 장소 | 도시공원, 도서관, 박물관·미술관, 관광지 | 우리 동네 동화, 장소 카드, 미션 카드 |
| 해양·부산성 | 해양생물, 박물관·미술관 표준데이터의 해양 관련 장소 | 부산 특화 동화, 해양 캐릭터, 관람 전후 동화책 |
| 기관 제휴 | 도서관, 박물관·미술관, 관광지, 도시공원 관리기관 | 기관용 체험 동화, 미션 카드, 활동지 |
| 역사·관광 | 관광지, 향토문화유적, 지정문화재 | 역사 탐방 동화, 관광기관 협력 |

## AI 동화책 고도화 기준 데이터 후보

| 우선순위 | 아이디어 | 확보할 CSV 후보 | 부산 적용 | 수익화 판단 |
| --- | --- | --- | --- | --- |
| 최추천 | 우리 동네 동화 | 도서관, 도시공원, 어린이공원, 문화시설, 박물관·미술관 | 부산 16개 구·군별 생활권 장소를 동화 배경으로 사용 | B2C 구독 + 기관 라이선스 |
| B2B 강화 | 장소 기반 기관 콘텐츠 패키지 | 도서관, 박물관·미술관, 관광지, 도시공원 | 도서관·박물관·관광기관별 체험 동화와 활동지 제공 | 기관 라이선스 |
| 관광 확장 | 역사 속 주인공 | 국가유산·문화유산, 박물관·미술관, 관광지·문화시설 데이터 | 영도, 자갈치, 피란수도 부산, 박물관 탐방 코스 | 관광기관 협력 |

## 우선 확보 CSV

| 순번 | CSV | 데이터 | 우선순위 |
| --- | --- | --- | --- |
| 1 | `data/processed/csv/neighborhood-story/urban-parks-story-seeds.csv` | 전국도시공원정보표준데이터 | 상 |
| 2 | `data/processed/csv/neighborhood-story/libraries-story-seeds.csv` | 전국도서관표준데이터 | 상 |
| 3 | `data/processed/csv/busan-storybook/museum-art-story-seeds.csv` | 전국박물관미술관정보표준데이터 | 상 |
| 4 | `data/processed/csv/busan-storybook/marine-life-story-seeds.csv` | 해양생물 데이터 | 중 |
| 5 | `data/processed/csv/history-tour-story/tourist-attractions-story-seeds.csv` | 전국관광지정보표준데이터 | 중 |
| 6 | `data/processed/csv/history-tour-story/khs-heritage-story-seeds.csv` | 국가유산청 국가유산 검색 Open API | 중 |

## CSV 공통 컬럼 후보

정제 CSV는 원본 컬럼을 모두 살리기보다, MVP 화면과 AI 프롬프트에 필요한 컬럼만 별도 파일로 만든다.

| 컬럼 | 설명 | 예시 |
| --- | --- | --- |
| `source_name` | 데이터 출처명 | 전국도시공원정보표준데이터 |
| `source_url` | 원본 또는 상세 페이지 URL | `https://...` |
| `reference_date` | 원본 데이터 기준일 | `2026-05-25` |
| `item_type` | 장소, 생물, 기관 제휴 후보 등 | `place` |
| `name_ko` | 화면에 표시할 이름 | 부산어린이대공원 |
| `region` | 부산 구·군 또는 행정구역 | 부산 영도구 |
| `address` | 주소 | 부산광역시 영도구 ... |
| `latitude` | 위도 | `35.0788` |
| `longitude` | 경도 | `129.0801` |
| `summary` | 아이디어 검토용 요약 | 바다와 항해를 주제로 한 가족 관람 장소 |
| `story_use` | 동화에서 쓰는 방식 | 관람 전 예습 동화의 배경 |
| `mission_use` | 미션에서 쓰는 방식 | 전시실에서 배 모양을 찾아보기 |
| `age_tags` | 적합 연령 | `5-7`, `8-10` |
| `keywords` | 검색·프롬프트 키워드 | 바다, 배, 고래, 항해 |

## 판단 기준

| 판단 질문 | 확인할 CSV |
| --- | --- |
| 공공데이터가 없으면 서비스가 성립하지 않는가? | 공원, 도서관, 박물관, 관광지, 해양생물 |
| 부산성이 바로 보이는가? | 부산 필터링된 공원, 도서관, 박물관, 관광지, 해양 관련 장소 |
| 아이가 실제로 쓸 화면으로 바뀌는가? | 장소 카드, 동화 생성, 미션 카드, 활동지 |
| B2B 고객 리스트로 이어지는가? | 도서관, 박물관, 관광기관, 구청 |
| 확장안으로 둘 데이터인가? | 국가유산, 관광지, 해양생물 |

## 관리 원칙

- 원본 데이터는 `raw`에 그대로 보관한다.
- 서비스에서 쓰는 필드만 `processed`로 정리한다.
- 발표와 테스트에 쓰는 고정 입력은 `samples`에 둔다.
- 데이터 출처 URL은 빈 값으로 두지 않고 제출 전 반드시 채운다.
- DB 적재는 CSV 후보와 MVP 화면이 정리된 뒤 다시 결정한다.

## Python 수집·백필 스크립트

설치 없이 Python 표준 라이브러리만 사용한다.

```bash
python3 -m scripts.data.public_data_pipeline list
python3 -m scripts.data.public_data_pipeline init
python3 -m scripts.data.public_data_pipeline download urban_parks
python3 -m scripts.data.public_data_pipeline backfill urban_parks
python3 -m scripts.data.public_data_pipeline backfill-all
```

고도화 데이터는 전국 확장 가능성을 유지해야 하므로 기본 백필은 부산 필터를 걸지 않는다. 부산 공모전 제출용 샘플이 필요할 때만 `--busan-only`를 붙인다.

```bash
python3 -m scripts.data.public_data_pipeline backfill urban_parks --busan-only
python3 -m scripts.data.public_data_pipeline backfill-all --busan-only
```

전국도시공원정보표준데이터는 공공데이터포털 Open API로 다운로드할 수 있다. 기본 인증키는 `data/public-datasets.json`에 들어 있으며, `DATA_GO_KR_API_KEY` 환경변수가 있으면 그 값을 우선 사용한다.

```bash
python3 -m scripts.data.public_data_pipeline download urban_parks
python3 -m scripts.data.public_data_pipeline backfill urban_parks
```

전국도서관표준데이터도 같은 인증키로 다운로드한다. 작은도서관은 같은 원본 CSV에서 `도서관유형`에 `작은`이 포함된 행만 별도 백필한다.

```bash
python3 -m scripts.data.public_data_pipeline download libraries
python3 -m scripts.data.public_data_pipeline backfill libraries
python3 -m scripts.data.public_data_pipeline backfill small_libraries
```

전국박물관미술관정보표준데이터도 API로 다운로드하고 story seed로 정제한다.

```bash
python3 -m scripts.data.public_data_pipeline download museum_art
python3 -m scripts.data.public_data_pipeline backfill museum_art
```

국립해양생물자원관 해양생물 채집 공간정보는 ODCLOUD API로 다운로드하고 해양생물 캐릭터·퀴즈용 story seed로 정제한다.

```bash
python3 -m scripts.data.public_data_pipeline download marine_life_collection
python3 -m scripts.data.public_data_pipeline backfill marine_life_collection
```

국립해양생물자원관 해양생물종정보 서비스는 XML API로 다운로드하고 해양생물 설명문·과학 퀴즈용 story seed로 정제한다.

```bash
python3 -m scripts.data.public_data_pipeline download marine_life_species
python3 -m scripts.data.public_data_pipeline backfill marine_life_species
```

전국관광지정보표준데이터도 API로 다운로드하고 탐방형 story seed로 정제한다.

```bash
python3 -m scripts.data.public_data_pipeline download tourist_attractions
python3 -m scripts.data.public_data_pipeline backfill tourist_attractions
```

전국향토문화유적표준데이터도 API로 다운로드하고 동네 역사 탐방형 story seed로 정제한다.

```bash
python3 -m scripts.data.public_data_pipeline download local_heritage
python3 -m scripts.data.public_data_pipeline backfill local_heritage
```

국가유산청 국가유산 검색 Open API는 공공데이터포털 표준 API와 달라 별도 모듈로 수집한다. 부산 시도코드 `21`의 목록을 받은 뒤 상세 API를 호출해 story seed로 정제한다.

```bash
python3 -m scripts.data.khs_heritage_pipeline all --insecure
python3 -m scripts.data.khs_heritage_pipeline download-list --insecure
python3 -m scripts.data.khs_heritage_pipeline download-details --insecure
python3 -m scripts.data.khs_heritage_pipeline backfill
```

로컬 인증서 문제로 `CERTIFICATE_VERIFY_FAILED`가 발생할 때만 임시로 `--insecure`를 붙인다.

```bash
python3 -m scripts.data.public_data_pipeline download urban_parks --insecure
```

`API error 30: SERVICE KEY IS NOT REGISTERED ERROR.`가 나오면 공공데이터포털의 Encoding/Decoding 인증키 중 다른 키로 재시도하거나, 승인 직후 반영 시간을 기다린 뒤 다시 실행한다.

현재 도시공원·도서관 API는 `pageNo=0` 샘플 페이지를 우선 저장하도록 `max_pages=1`로 둔다. 전체 페이지 수집은 페이지 1 이후 키 오류가 해결되면 `data/public-datasets.json`에서 `max_pages`를 늘리거나 제거한다.

공공데이터포털 CSV는 직접 다운로드 URL이 확인되기 전까지 `download_url`을 비워둔다. 이 경우 `download` 명령은 출처 메모를 만들고, 사용자가 저장해야 할 원본 CSV 경로를 알려준다.
