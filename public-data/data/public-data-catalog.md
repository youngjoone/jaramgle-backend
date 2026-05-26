# Public Data Catalog

부산 특화 AI 동화책 생성 플랫폼에 필요한 공공데이터를 정리한다.

이 문서는 아이디어를 많이 늘리기 위한 문서가 아니라, **하나의 서비스 축**과 그 안에서 확장 가능한 사업화 모듈에 필요한 데이터만 관리한다.

## 서비스 축

**부산 특화 AI 동화책 생성 플랫폼**

부산의 생활권 장소, 해양생물, 역사·관광 공공데이터를 아이의 나이와 상황에 맞는 동화책, 활동지, 영상북, 기관용 콘텐츠로 변환한다.

## 데이터 상태값

| 상태 | 의미 |
| --- | --- |
| `confirmed` | 공식 데이터 URL과 주요 컬럼을 확인했다. |
| `api-to-csv` | API형 데이터라 스크립트로 CSV 변환이 필요하다. |
| `manual-or-scrape` | 공식 페이지는 있으나 CSV 제공 여부가 불명확해 수동 CSV 또는 스크래핑 검토가 필요하다. |
| `tbd` | 후보만 있고 공식 URL·컬럼 확인이 필요하다. |

## 저장 규칙

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
```

- `raw/csv`: 원본 또는 API 변환 CSV를 그대로 저장한다.
- `processed/csv`: 컬럼 정리와 서비스용 태그 추가 결과를 저장한다. 부산 필터링은 공모전 샘플이 필요할 때만 옵션으로 수행한다.
- `processed/json`: 프론트/백엔드 MVP에서 바로 읽기 쉬운 JSON으로 변환한 결과를 저장한다.
- 데이터별 다운로드 방식, API 조건, 이용 조건은 이 카탈로그 문서에 모아 관리한다.

## 1. 부산 특화 동화책 생성 핵심 데이터

부산 특화 동화책 생성의 기본 데이터는 아래 5개 레이어로 본다.

| 레이어 | 역할 | 데이터 |
| --- | --- | --- |
| 생활권 장소 | 아이 주변 배경을 만든다. | 도시공원, 도서관, 박물관·미술관, 관광지 |
| 해양·부산성 | 부산다운 소재를 만든다. | 해양생물, 박물관·미술관 표준데이터의 해양 관련 장소 |
| 기관 제휴 | B2B 납품 대상으로 확장한다. | 도서관, 박물관·미술관, 관광지, 도시공원 관리기관 |
| 역사·관광 | 탐방형 동화로 확장한다. | 관광지, 지정문화재, 향토문화유적 |

## 2. 핵심 데이터 수집 목록

| 우선순위 | 상태 | 데이터명 | URL | 원본 저장 파일 | 정제 저장 파일 | 쓰임 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `confirmed` | 전국도시공원정보표준데이터 | `https://www.data.go.kr/data/15012890/standard.do` / API `https://api.data.go.kr/openapi/tn_pubr_public_cty_park_info_api` | `data/raw/csv/neighborhood-story/urban-parks-standard-all.csv` | `data/processed/csv/neighborhood-story/urban-parks-story-seeds.csv` | 우리 동네 동화, 어린이공원 배경 |
| 1 | `confirmed` | 전국도서관표준데이터 | `https://www.data.go.kr/data/15013109/standard.do` / API `https://api.data.go.kr/openapi/tn_pubr_public_lbrry_api` | `data/raw/csv/neighborhood-story/libraries-standard-all.csv` | `data/processed/csv/neighborhood-story/libraries-story-seeds.csv` | 도서관 동화, 작은도서관 확장, 기관 제휴 |
| 1 | `confirmed` | 전국박물관미술관정보표준데이터 | `https://www.data.go.kr/data/15017323/standard.do` / API `https://api.data.go.kr/openapi/tn_pubr_public_museum_artgr_info_api` | `data/raw/csv/busan-storybook/museum-art-standard-all.csv` | `data/processed/csv/busan-storybook/museum-art-story-seeds.csv` | 문화공간 동화, 박물관 미션 |
| 2 | `confirmed` | 전국관광지정보표준데이터 | `https://www.data.go.kr/data/15021141/standard.do` / API `https://api.data.go.kr/openapi/tn_pubr_public_trrsrt_api` | `data/raw/csv/history-tour-story/tourist-attractions-standard-all.csv` | `data/processed/csv/history-tour-story/tourist-attractions-story-seeds.csv` | 탐방 동화, 관광기관 협력 |
| 2 | `confirmed` | 국립해양생물자원관_해양생물 채집 공간정보 | `https://www.data.go.kr/data/15151832/openapi.do` / API `https://api.odcloud.kr/api/15151832/v1/uddi:ff9fb315-9efe-4533-98b4-8b058b3ad66d` | `data/raw/csv/busan-storybook/marine-life-collection-spatial.csv` | `data/processed/csv/busan-storybook/marine-life-story-seeds.csv` | 해양생물 캐릭터, 퀴즈 |
| 2 | `confirmed` | 국립해양생물자원관_해양생물종정보 서비스 | `https://www.data.go.kr/data/15094770/openapi.do` / API `https://apis.data.go.kr/B553482/mbrisdataview3/taxonlist3` | `data/raw/csv/busan-storybook/marine-life-species-api-sample.csv` | `data/processed/csv/busan-storybook/marine-life-species-story-seeds.csv` | 해양생물 설명문 보강 |
| 3 | `confirmed` | 전국향토문화유적표준데이터 | `https://www.data.go.kr/data/15021147/standard.do` / API `https://api.data.go.kr/openapi/tn_pubr_public_nvpc_cltur_relics_api` | `data/raw/csv/history-tour-story/local-heritage-standard-all.csv` | `data/processed/csv/history-tour-story/local-heritage-story-seeds.csv` | 동네 역사 동화 |
| 3 | `confirmed` | 국가유산청_국가유산 검색 Open API | `https://www.khs.go.kr/cha/SearchKindOpenapiList.do` / 상세 API `https://www.khs.go.kr/cha/SearchKindOpenapiDt.do` | `data/raw/csv/history-tour-story/khs-heritage-list-busan.csv`, `data/raw/csv/history-tour-story/khs-heritage-detail-busan.csv` | `data/processed/csv/history-tour-story/khs-heritage-story-seeds.csv` | 역사 속 주인공, 국가유산 탐방 동화 |

## 3. 확인된 데이터별 컬럼

### 3-1. 전국도시공원정보표준데이터

서비스 모듈: `우리 동네 동화`

API 정보:

| 항목 | 내용 |
| --- | --- |
| End Point | `https://api.data.go.kr/openapi/tn_pubr_public_cty_park_info_api` |
| 인증키 환경변수 | `DATA_GO_KR_API_KEY` |
| 데이터포맷 | JSON+XML |
| 기본 요청 | `pageNo`, `numOfRows`, `type=json` |
| 상세 조회 파라미터 | `MANAGE_NO`, `PARK_NM`, `PARK_SE`, `RDNMADR`, `LNMADR`, `LATITUDE`, `LONGITUDE`, `PARK_AR`, `MVM_FCLTY`, `AMSMT_FCLTY`, `CNVNNC_FCLTY`, `CLTR_FCLTY`, `ETC_FCLTY`, `APPN_NTFC_DATE`, `INSTITUTION_NM`, `PHONE_NUMBER`, `REFERENCE_DATE`, `instt_code`, `instt_nm` |
| 라이선스 | 저작자표시, 제3자 권리 포함 |
| 활용 신청 상태 | 개발계정 승인 |

| 컬럼 | 의미 | 서비스 활용 |
| --- | --- | --- |
| `관리번호` | 공원 고유 관리번호 | 중복 제거 |
| `공원명` | 공원명 | 동화 배경 이름 |
| `공원구분` | 어린이공원, 근린공원 등 | 아이 친화 장소 필터 |
| `소재지도로명주소` | 도로명주소 | 부산/구·군 필터 |
| `소재지지번주소` | 지번주소 | 보조 주소 |
| `위도` | 위도 | 지도, 주변 장소 추천 |
| `경도` | 경도 | 지도, 주변 장소 추천 |
| `공원면적` | 공원 규모 | 장소 설명 |
| `공원보유시설(운동시설)` | 운동시설 | 활동 미션 소재 |
| `공원보유시설(유희시설)` | 놀이시설 | 어린이 미션 소재 |
| `공원보유시설(편익시설)` | 편의시설 | 부모 안내 |
| `공원보유시설(교양시설)` | 교양시설 | 교육 소재 |
| `공원보유시설(기타시설)` | 기타시설 | 보조 소재 |
| `지정고시일` | 지정일 | 출처 보조 |
| `관리기관명` | 관리기관 | B2G/B2B 후보 |
| `전화번호` | 문의처 | 기관 연락 |
| `데이터기준일자` | 기준일 | 제출 증빙 |

### 3-2. 전국도서관표준데이터

서비스 모듈: `우리 동네 동화`, `B2B 기관 라이선스`

API 정보:

| 항목 | 내용 |
| --- | --- |
| End Point | `https://api.data.go.kr/openapi/tn_pubr_public_lbrry_api` |
| 인증키 환경변수 | `DATA_GO_KR_API_KEY` |
| 데이터포맷 | JSON+XML |
| 기본 요청 | `pageNo`, `numOfRows`, `type=json` |
| 상세 조회 파라미터 | `LBRRY_NM`, `CTPRVN_NM`, `SIGNGU_NM`, `LBRRY_SE`, `CLOSE_DAY`, `WEEKDAY_OPER_OPEN_HHMM`, `WEEKDAY_OPER_COLSE_HHMM`, `SAT_OPER_OPER_OPEN_HHMM`, `SAT_OPER_CLOSE_HHMM`, `HOLIDAY_OPER_OPEN_HHMM`, `HOLIDAY_CLOSE_OPEN_HHMM`, `SEAT_CO`, `BOOK_CO`, `PBLICTN_CO`, `NONE_BOOK_CO`, `LON_CO`, `LON_DAYCNT`, `RDNMADR`, `OPER_INSTITUTION_NM`, `PHONE_NUMBER`, `PLOT_AR`, `BULD_AR`, `HOMEPAGE_URL`, `LATITUDE`, `LONGITUDE`, `REFERENCE_DATE`, `instt_code`, `instt_nm` |
| 라이선스 | 저작자표시, 제3자 권리 포함 |
| 활용 신청 상태 | 개발계정 승인 |

| 컬럼 | 의미 | 서비스 활용 |
| --- | --- | --- |
| `도서관명` | 도서관명 | 동화 배경 이름 |
| `시도명` | 시도 | 부산 필터 |
| `시군구명` | 구·군 | 지역별 동화 |
| `도서관유형` | 공공도서관, 작은도서관 등 | 작은도서관 필터 |
| `휴관일` | 휴관일 | 방문 전 안내 |
| `평일운영시작시각` | 평일 시작 | 방문 전 안내 |
| `평일운영종료시각` | 평일 종료 | 방문 전 안내 |
| `토요일운영시작시각` | 토요일 시작 | 방문 전 안내 |
| `토요일운영종료시각` | 토요일 종료 | 방문 전 안내 |
| `공휴일운영시작시각` | 공휴일 시작 | 방문 전 안내 |
| `공휴일운영종료시각` | 공휴일 종료 | 방문 전 안내 |
| `열람좌석수` | 좌석 규모 | 장소 설명 |
| `자료수(도서)` | 장서 수 | 독서 공간 설명 |
| `자료수(연속간행물)` | 연속간행물 수 | 보조 설명 |
| `자료수(비도서)` | 비도서 수 | 오디오·영상 자료 힌트 |
| `대출가능권수` | 대출 권수 | 부모 안내 |
| `대출가능일수` | 대출 일수 | 부모 안내 |
| `소재지도로명주소` | 주소 | 지도, 지역 필터 |
| `운영기관명` | 운영기관 | 기관 영업 후보 |
| `도서관전화번호` | 연락처 | 기관 연락 |
| `홈페이지주소` | 홈페이지 | 상세 링크 |
| `위도` | 위도 | 지도 |
| `경도` | 경도 | 지도 |
| `데이터기준일자` | 기준일 | 제출 증빙 |

### 3-3. 전국박물관미술관정보표준데이터

서비스 모듈: `우리 동네 동화`, `역사 속 주인공`, `관람 미션`

API 정보:

| 항목 | 내용 |
| --- | --- |
| End Point | `https://api.data.go.kr/openapi/tn_pubr_public_museum_artgr_info_api` |
| 인증키 | `data/public-datasets.json` 기본키 또는 `DATA_GO_KR_API_KEY` |
| 데이터포맷 | JSON+XML |
| 기본 요청 | `pageNo`, `numOfRows`, `type=json` |
| 상세 조회 파라미터 | `fcltyNm`, `fcltyType`, `rdnmadr`, `lnmadr`, `latitude`, `longitude`, `operPhoneNumber`, `operInstitutionNm`, `homepageUrl`, `fcltyInfo`, `weekdayOperOpenHhmm`, `weekdayOperColseHhmm`, `holidayOperOpenHhmm`, `holidayCloseOpenHhmm`, `rstdeInfo`, `adultChrge`, `yngbgsChrge`, `childChrge`, `etcChrgeInfo`, `fcltyIntrcn`, `trnsportInfo`, `phoneNumber`, `institutionNm`, `referenceDate`, `instt_code` |
| 라이선스 | 저작자표시, 제3자 권리 포함, 공공저작물 출처표시 제1유형 |
| 활용 신청 상태 | 개발계정 승인 |

| 컬럼 | 의미 | 서비스 활용 |
| --- | --- | --- |
| `시설명` | 박물관·미술관명 | 동화 배경 |
| `박물관미술관구분` | 공립, 사립 등 | 장소 유형 |
| `소재지도로명주소` | 도로명주소 | 부산 필터 |
| `소재지지번주소` | 지번주소 | 보조 주소 |
| `위도` | 위도 | 지도 |
| `경도` | 경도 | 지도 |
| `운영기관전화번호` | 운영기관 연락처 | 기관 연락 |
| `운영기관명` | 운영기관 | B2B 후보 |
| `운영홈페이지` | 홈페이지 | 상세 링크 |
| `편의시설정보` | 편의시설 | 부모 안내 |
| `평일관람시작시각` | 평일 시작 | 방문 안내 |
| `평일관람종료시각` | 평일 종료 | 방문 안내 |
| `공휴일관람시작시각` | 공휴일 시작 | 방문 안내 |
| `공휴일관람종료시각` | 공휴일 종료 | 방문 안내 |
| `휴관정보` | 휴관일 | 방문 안내 |
| `어른관람료` | 성인 요금 | 부모 안내 |
| `청소년관람료` | 청소년 요금 | 부모 안내 |
| `어린이관람료` | 어린이 요금 | 부모 안내 |
| `관람료기타정보` | 기타 요금 | 부모 안내 |
| `박물관미술관소개` | 소개 | 동화 배경 요약 |
| `교통안내정보` | 교통 | 방문 안내 |
| `관리기관전화번호` | 관리기관 연락처 | 출처 보조 |
| `관리기관명` | 관리기관 | 출처 보조 |
| `데이터기준일자` | 기준일 | 제출 증빙 |

### 3-4. 국립해양생물자원관_해양생물 채집 공간정보

서비스 모듈: `부산 특화 동화`, `해양생물 캐릭터`, `관찰 퀴즈`

API 정보:

| 항목 | 내용 |
| --- | --- |
| Base URL | `api.odcloud.kr/api` |
| End Point | `https://api.odcloud.kr/api/15151832/v1/uddi:ff9fb315-9efe-4533-98b4-8b058b3ad66d` |
| Swagger URL | `https://infuser.odcloud.kr/oas/docs?namespace=15151832/v1` |
| 인증키 | `data/public-datasets.json` 기본키 또는 `DATA_GO_KR_API_KEY` |
| 데이터포맷 | JSON+XML |
| 기본 요청 | `page`, `perPage`, `returnType=JSON` |
| 상세 조회 파라미터 | `page`, `perPage`, `returnType` |
| 활용 신청 상태 | 개발계정 승인 |

| 컬럼 | 의미 | 서비스 활용 |
| --- | --- | --- |
| `학명` | 생물 학명 | 과학 정보, 상세 키워드 |
| `국명` | 생물 국문명 | 캐릭터 이름, 화면 표시명 |
| `분류체계` | 생물 분류 | 생물군별 필터, 퀴즈 |
| `채집일` | 채집 일자 | 기준일, 계절 소재 |
| `위도` | 채집 위도 | 지도, 바다 탐험 배경 |
| `경도` | 채집 경도 | 지도, 바다 탐험 배경 |
| `국가` | 채집 국가 | 지역 범위 |
| `해역` | 채집 해역 | 부산/해양성 소재 |
| `지역` | 채집 지역 | 장소 기반 이야기 소재 |

### 3-5. 국립해양생물자원관_해양생물종정보 서비스

서비스 모듈: `부산 특화 동화`, `해양생물 설명문`, `과학 퀴즈`

API 정보:

| 항목 | 내용 |
| --- | --- |
| End Point | `https://apis.data.go.kr/B553482/mbrisdataview3/taxonlist3` |
| 인증키 | `data/public-datasets.json` 기본키 또는 `DATA_GO_KR_API_KEY` |
| 데이터포맷 | XML |
| 기본 요청 | `pageNo`, `numOfRows` |
| 상세 조회 파라미터 | `SpcScitfNm`, `CommKorNm`, `Family`, `FamilyKR`, `SpcTxnId` |
| 일일 트래픽 | 8000 |
| 라이선스 | 이용허락범위 제한 없음 |
| 활용 신청 상태 | 개발계정 승인 |

| 컬럼 | 의미 | 서비스 활용 |
| --- | --- | --- |
| `SpcTxnId` | 종 식별자 번호 | 중복 제거, 상세 조회 키 |
| `Kingdom`, `KingdomKR` | 계 | 생물 분류 설명 |
| `PhylumDivision`, `PhylumDivisionKR` | 문 | 생물 분류 설명 |
| `Class`, `ClassKR` | 강 | 생물 분류 설명 |
| `Order`, `OrderKR` | 목 | 생물 분류 설명 |
| `Family`, `FamilyKR` | 과 | 생물군 필터, 퀴즈 |
| `SpcScitfNm` | 학명 | 과학 정보, 검색 키 |
| `SpcScitfNmShort` | 축약 학명 | 화면 보조명 |
| `CommKorNm` | 국명 | 캐릭터 이름, 화면 표시명 |
| `SpcTyp` | 종 유형 | 설명 보조 |
| `ABST` | 개요 | 동화 과학 지식 본문 |
| `FORM` | 형태 | 생김새 묘사, 삽화 프롬프트 |
| `ECOL` | 생태 | 미션·퀴즈 소재 |
| `NADI` | 국내 분포 | 지역·바다 배경 |
| `INDI` | 국외 분포 | 세계 바다 확장 소재 |
| `HABI` | 서식지 | 관찰 질문, 배경 설정 |
| `UTLZ` | 활용 정보 | 부모 안내, 보조 설명 |
| `CorrNmTyp`, `CorrSpcScitfNm` | 정명 정보 | 명칭 정리 |

### 3-6. 관광지·문화유적·국가유산

서비스 모듈: `역사 속 주인공`

| 데이터 | 주요 컬럼 | 서비스 활용 |
| --- | --- | --- |
| 전국관광지정보표준데이터 | `관광지명`, `관광지구분`, `소재지도로명주소`, `위도`, `경도`, `공공편익시설정보`, `휴양및문화시설정보`, `수용인원수`, `주차가능수`, `관광지소개`, `관리기관명`, `데이터기준일자` | 지역 탐방 동화, 관광기관 협력 |
| 전국향토문화유적표준데이터 | `향토문화유적명`, `문화유적지정번호`, `향토문화유적구분`, `향토문화유적종류`, `소재지도로명주소`, `위도`, `경도`, `지정일자`, `조성시대`, `이미지정보`, `향토문화유적소개`, `관리기관명` | 동네 역사 동화 |
| 국가유산청_전국 지정문화재 현황 | `문화재명`, `지정일`, `소재시도`, `설명내용`, `사진`, `동영상`, `음성파일` 후보 | 역사 속 주인공, 오디오/영상북 보강 |

전국관광지정보표준데이터 API 정보:

| 항목 | 내용 |
| --- | --- |
| End Point | `https://api.data.go.kr/openapi/tn_pubr_public_trrsrt_api` |
| 인증키 | `data/public-datasets.json` 기본키 또는 `DATA_GO_KR_API_KEY` |
| 데이터포맷 | JSON+XML |
| 기본 요청 | `pageNo`, `numOfRows`, `type=json` |
| 상세 조회 파라미터 | `trrsrtNm`, `trrsrtSe`, `rdnmadr`, `lnmadr`, `latitude`, `longitude`, `ar`, `cnvnncFclty`, `stayngInfo`, `mvmAmsmtFclty`, `recrtClturFclty`, `hospitalityFclty`, `sportFclty`, `appnDate`, `aceptncCo`, `prkplceCo`, `trrsrtIntrcn`, `phoneNumber`, `institutionNm`, `referenceDate`, `instt_code` |
| 라이선스 | 저작자표시, 제3자 권리 포함 |
| 활용 신청 상태 | 개발계정 승인 |

전국향토문화유적표준데이터 API 정보:

| 항목 | 내용 |
| --- | --- |
| End Point | `https://api.data.go.kr/openapi/tn_pubr_public_nvpc_cltur_relics_api` |
| 인증키 | `data/public-datasets.json` 기본키 또는 `DATA_GO_KR_API_KEY` |
| 데이터포맷 | JSON+XML |
| 기본 요청 | `pageNo`, `numOfRows`, `type=json` |
| 상세 조회 파라미터 | `relicsNm`, `appnNo`, `relicsKnd`, `relicsSe`, `rdnmadr`, `lnmadr`, `latitude`, `longitude`, `appnDate`, `posesnSe`, `ownerNm`, `scale`, `makePd`, `picInfo`, `relicsIntrcn`, `phoneNumber`, `institutionNm`, `referenceDate`, `instt_code` |
| 라이선스 | 저작자표시, 제3자 권리 포함 |
| 활용 신청 상태 | 개발계정 승인 |

국가유산청_국가유산 검색 Open API 정보:

| 항목 | 내용 |
| --- | --- |
| API 성격 | 국가유산청 자체 XML Open API. 공공데이터포털 인증키를 쓰지 않는다. |
| 목록 End Point | `https://www.khs.go.kr/cha/SearchKindOpenapiList.do` |
| 상세 End Point | `https://www.khs.go.kr/cha/SearchKindOpenapiDt.do` |
| 부산 시도코드 | `21` |
| 목록 기본 요청 | `pageUnit`, `pageIndex`, `ccbaCncl=N`, `ccbaCtcd=21` |
| 상세 필수 요청 | `ccbaKdcd`, `ccbaAsno`, `ccbaCtcd` |
| 목록 주요 컬럼 | `totalCnt`, `ccmaName`, `ccbaMnm1`, `ccbaMnm2`, `ccbaCtcdNm`, `ccsiName`, `ccbaAdmin`, `ccbaKdcd`, `ccbaCtcd`, `ccbaAsno`, `longitude`, `latitude`, `regDt` |
| 상세 주요 컬럼 | `gcodeName`, `bcodeName`, `mcodeName`, `scodeName`, `ccbaQuan`, `ccbaAsdt`, `ccbaLcad`, `ccceName`, `ccbaPoss`, `ccbaAdmin`, `imageUrl`, `content` |
| 수집 모듈 | `scripts/data/khs_heritage_pipeline.py` |
| 주의 | 이미지 활용 전 `SearchImageOpenapi.do`의 `imageNuri` 공공누리 유형을 별도 확인한다. |

## 4. 사업화 모듈별 데이터 정리

### 4-1. 최추천: 우리 동네 동화

| 항목 | 내용 |
| --- | --- |
| 목표 | 아이의 거주지·현재 위치 주변 공원, 도서관, 문화공간, 박물관을 배경으로 초개인화 동화를 생성한다. |
| 확보할 데이터 | 도시공원, 도서관, 박물관·미술관, 관광지, 해양생물 |
| 부산 적용 | 16개 구·군별 생활권 장소를 동화 배경으로 사용한다. |
| 수익화 | B2C 월 구독, 도서관·구청·박물관 B2B 라이선스 |
| 1차 CSV | `urban-parks-story-seeds.csv`, `libraries-story-seeds.csv`, `museum-art-story-seeds.csv` |
| MVP 화면 | 동네 선택, 장소 카드, 아이 맞춤 동화 생성, 미션 카드 |

### 4-2. B2B 강화: 장소 기반 기관 콘텐츠 패키지

| 항목 | 내용 |
| --- | --- |
| 목표 | 도서관·박물관·관광기관이 바로 쓸 수 있는 체험 동화, 미션 카드, 활동지를 생성한다. |
| 확보할 데이터 | 도서관, 박물관·미술관, 관광지, 도시공원 |
| 부산 적용 | 부산 기관별 장소 데이터로 전시·체험 전후 콘텐츠를 만든다. |
| 수익화 | 기관 라이선스, 행사형 콘텐츠 제작, 관광·교육 캠페인 |
| 1차 CSV | `libraries-story-seeds.csv`, `museum-art-story-seeds.csv`, `tourist-attractions-story-seeds.csv` |
| MVP 화면 | 기관/장소 선택, 체험 동화 생성, 미션 카드, 활동지 PDF |

### 4-3. 관광 확장: 역사 속 주인공

| 항목 | 내용 |
| --- | --- |
| 목표 | 문화유산·역사 장소·박물관 정보를 아이가 주인공인 탐방 동화로 변환한다. |
| 확보할 데이터 | 관광지, 향토문화유적, 지정문화재, 박물관·미술관 |
| 부산 적용 | 영도, 자갈치, 피란수도 부산, 박물관·문화재 탐방 코스를 동화 여행으로 연결 |
| 수익화 | 관광기관 협력, 박물관 교육 라이선스, AR 탐방 콘텐츠 |
| 1차 CSV | `tourist-attractions-story-seeds.csv`, `local-heritage-story-seeds.csv`, `khs-heritage-story-seeds.csv`, `museum-art-story-seeds.csv` |
| MVP 화면 | 탐방 코스 선택, 역사 인물/장소 동화 생성, 방문 미션 |

## 5. 수집 순서

1. `전국도시공원정보표준데이터`, `전국도서관표준데이터`, `전국박물관미술관정보표준데이터`를 CSV로 확보하고 전국 단위 story seed로 정제한다.
2. `전국관광지정보표준데이터`로 생활권 장소와 관광 확장성을 보강한다.
3. 해양생물 데이터는 공공데이터포털 제공 데이터로만 붙인다.
4. `전국향토문화유적표준데이터`와 국가유산청 Open API 데이터로 역사 확장성을 보강한다.

## 6. 수집 스크립트

| 스크립트 | 역할 |
| --- | --- |
| `scripts/data/public_data_pipeline.py` | 데이터셋 목록 확인, 수동 CSV 템플릿 생성, API 다운로드, 정제 CSV 백필을 담당한다. 부산 필터링은 `--busan-only` 옵션으로만 수행한다. |
| `scripts/data/khs_heritage_pipeline.py` | 국가유산청 자체 XML API에서 부산 국가유산 목록, 상세, story seed CSV를 수집한다. |

실행 예시:

```bash
python3 -m scripts.data.public_data_pipeline list
python3 -m scripts.data.public_data_pipeline init
python3 -m scripts.data.public_data_pipeline backfill urban_parks
python3 -m scripts.data.public_data_pipeline backfill-all
python3 -m scripts.data.public_data_pipeline backfill urban_parks --busan-only
```
