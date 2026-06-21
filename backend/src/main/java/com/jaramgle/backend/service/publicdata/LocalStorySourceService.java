package com.jaramgle.backend.service.publicdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaramgle.backend.dto.publicdata.LocalStorySourceDto;
import com.jaramgle.backend.dto.publicdata.LocalStorySourcePageDto;
import com.jaramgle.backend.entity.LocalStorySource;
import com.jaramgle.backend.repository.LocalStorySourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class LocalStorySourceService {

    private static final String EXTERNAL_SOURCE_KTO_TOUR = "KTO_TOUR_API";
    private static final String KTO_AREA_BASED_LIST_API = "https://apis.data.go.kr/B551011/KorService2/areaBasedList2";
    private static final String KTO_DETAIL_COMMON_API = "https://apis.data.go.kr/B551011/KorService2/detailCommon2";
    private static final String KTO_PHOTO_GALLERY_SEARCH_API_URL = "https://apis.data.go.kr/B551011/PhotoGalleryService1/gallerySearchList1";
    private static final int DEFAULT_SIZE = 12;
    private static final int MAX_SIZE = 24;
    private static final int PHOTO_SEARCH_ROWS = 5;
    private static final int PHOTO_CACHE_MAX_SIZE = 512;
    private static final int MAX_STORY_CONTEXT_CHARS = 320;

    private static final Map<String, RegionProfile> REGION_PROFILES = Map.of(
            "DAEGU", new RegionProfile("DAEGU", "대구", "4", "근대골목과 시장, 도시의 시간여행"),
            "CHUNGBUK", new RegionProfile("CHUNGBUK", "충북", "33", "호수와 숲, 청풍명월의 자연 탐험")
    );

    @Value("${local.public-data.service-key:${KTO_SERVICE_KEY:${PUBLIC_DATA_SERVICE_KEY:${BUSAN_PUBLIC_DATA_SERVICE_KEY:}}}}")
    private String serviceKey;

    @Value("${local.public-data.photo-service-key:${KTO_PHOTO_SERVICE_KEY:${PUBLIC_DATA_SERVICE_KEY:${BUSAN_PUBLIC_DATA_SERVICE_KEY:}}}}")
    private String photoServiceKey;

    @Value("${local.public-data.photo-enabled:${LOCAL_PHOTO_ENRICHMENT_ENABLED:true}}")
    private boolean photoEnrichmentEnabled;

    @Value("${local.public-data.detail-enabled:${LOCAL_PUBLIC_DATA_DETAIL_ENABLED:false}}")
    private boolean detailFetchEnabled;

    @Value("${local.public-data.sync-enabled:${LOCAL_PUBLIC_DATA_SYNC_ENABLED:false}}")
    private boolean scheduledSyncEnabled;

    @Value("${local.public-data.sync-page-size:${LOCAL_PUBLIC_DATA_SYNC_PAGE_SIZE:24}}")
    private int syncPageSize;

    @Value("${local.public-data.sync-max-pages:${LOCAL_PUBLIC_DATA_SYNC_MAX_PAGES:8}}")
    private int syncMaxPages;

    private final ObjectMapper objectMapper;
    private final LocalStorySourceRepository localStorySourceRepository;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    private volatile boolean photoApiDisabled = false;

    private final Map<String, Optional<PhotoMatch>> photoCache = Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Optional<PhotoMatch>> eldest) {
                    return size() > PHOTO_CACHE_MAX_SIZE;
                }
            }
    );

    public LocalStorySourcePageDto getSources(String region, int page, int size, String keyword, String sourceId) {
        RegionProfile profile = profile(region);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(MAX_SIZE, size <= 0 ? DEFAULT_SIZE : size));
        String normalizedKeyword = normalizeKeyword(keyword);
        String normalizedSourceId = normalizeSourceId(sourceId);

        if (normalizedSourceId != null) {
            return findBySourceId(profile.code(), normalizedSourceId);
        }

        Pageable pageable = PageRequest.of(
                safePage - 1,
                safeSize,
                Sort.by(Sort.Order.desc("qualityScore"), Sort.Order.asc("title"))
        );
        Page<LocalStorySource> result = normalizedKeyword == null
                ? localStorySourceRepository.findVisible(profile.code(), pageable)
                : localStorySourceRepository.searchVisible(profile.code(), normalizedKeyword, pageable);

        List<LocalStorySourceDto> items = result.getContent().stream()
                .map(this::toDto)
                .toList();
        return new LocalStorySourcePageDto(items, safePage, safeSize, result.getTotalElements(), result.hasNext());
    }

    public boolean hasActiveSources(String region) {
        return localStorySourceRepository.countByRegionCodeAndActiveTrue(profile(region).code()) > 0;
    }

    public StatusResult getStatus(String region) {
        RegionProfile profile = profile(region);
        long activeCount = localStorySourceRepository.countByRegionCodeAndActiveTrue(profile.code());
        long visibleCount = localStorySourceRepository.countVisible(profile.code());
        long photoEnrichedCount = localStorySourceRepository.countPhotoEnriched(profile.code());
        LocalDateTime latestSyncedAt = localStorySourceRepository.findLatestSyncedAt(profile.code());
        return new StatusResult(profile.code(), activeCount > 0, activeCount, visibleCount, photoEnrichedCount, latestSyncedAt);
    }

    public SyncResult syncFromPublicData(String region) {
        RegionProfile profile = profile(region);
        if (serviceKey == null || serviceKey.isBlank()) {
            log.warn("Local story source service key is not configured. Sync skipped for {}.", profile.code());
            return new SyncResult(profile.code(), 0, 0, 0, 0, "SERVICE_KEY_MISSING");
        }

        int pageSize = Math.max(1, Math.min(MAX_SIZE, syncPageSize));
        int maxPages = Math.max(1, syncMaxPages);
        int fetched = 0;
        int saved = 0;
        int skipped = 0;
        int failedPages = 0;
        LocalDateTime syncedAt = LocalDateTime.now();

        for (int pageNo = 1; pageNo <= maxPages; pageNo++) {
            List<LocalStorySourceDto> pageItems = fetchExternalPage(profile, pageNo, pageSize);
            if (pageItems.isEmpty()) {
                if (pageNo == 1) {
                    failedPages++;
                }
                break;
            }

            fetched += pageItems.size();
            for (LocalStorySourceDto item : pageItems) {
                if (!hasImage(item)) {
                    skipped++;
                    continue;
                }
                upsertStorySource(item, syncedAt);
                saved++;
            }

            if (pageItems.size() < pageSize) {
                break;
            }
        }

        SyncResult result = new SyncResult(profile.code(), fetched, saved, skipped, failedPages, "OK");
        log.info("Local story source sync completed: {}", result);
        return result;
    }

    public PhotoEnrichmentResult enrichPhotoData(String region, int maxItems) {
        RegionProfile profile = profile(region);
        int safeMax = Math.max(1, Math.min(50, maxItems <= 0 ? 12 : maxItems));
        Pageable pageable = PageRequest.of(0, safeMax, Sort.by(Sort.Order.desc("qualityScore"), Sort.Order.asc("title")));
        List<LocalStorySource> candidates = localStorySourceRepository.findPhotoEnrichmentCandidates(profile.code(), pageable).getContent();
        int enriched = 0;
        int skipped = 0;

        for (LocalStorySource source : candidates) {
            Optional<PhotoMatch> match = findPhotoMatch(source.getTitle(), source.getDistrict());
            if (match.isEmpty()) {
                skipped++;
                continue;
            }

            PhotoMatch photo = match.get();
            String feature = firstNonBlank(source.getFeature(), buildPhotoFeature(photo));
            source.setImageUrl(firstNonBlank(source.getImageUrl(), photo.imageUrl()));
            source.setThumbnailUrl(firstNonBlank(source.getThumbnailUrl(), photo.imageUrl(), source.getImageUrl()));
            source.setFeature(feature);
            source.setStoryContext(buildStoryContext(
                    source.getIntro(),
                    feature,
                    source.getOrigin(),
                    firstNonBlank(source.getStoryContext(), buildPhotoFeature(photo))
            ));
            source.setPhotoTitle(trimToLength(photo.title(), 255));
            source.setPhotoLocation(trimToLength(photo.location(), 255));
            source.setPhotoKeywords(photo.keywords());
            source.setDataSources(appendDataSource(source.getDataSources(), "한국관광공사 관광사진정보"));
            source.setQualityScore(score(toDto(source)));
            source.setLastSyncedAt(LocalDateTime.now());
            localStorySourceRepository.save(source);
            enriched++;
        }

        PhotoEnrichmentResult result = new PhotoEnrichmentResult(profile.code(), candidates.size(), enriched, skipped);
        log.info("Local photo enrichment completed: {}", result);
        return result;
    }

    @Scheduled(cron = "${local.public-data.sync-cron:${LOCAL_PUBLIC_DATA_SYNC_CRON:0 30 3 * * *}}")
    public void scheduledSync() {
        if (!scheduledSyncEnabled) {
            return;
        }
        REGION_PROFILES.keySet().forEach(this::syncFromPublicData);
    }

    public List<String> supportedRegions() {
        return REGION_PROFILES.keySet().stream().sorted().toList();
    }

    private LocalStorySourcePageDto findBySourceId(String regionCode, String sourceId) {
        return localStorySourceRepository.findFirstByRegionCodeAndActiveTrueAndExternalIdOrderByQualityScoreDesc(regionCode, sourceId)
                .map(source -> new LocalStorySourcePageDto(List.of(toDto(source)), 1, 1, 1L, false))
                .orElseGet(() -> new LocalStorySourcePageDto(List.of(), 1, 1, 0L, false));
    }

    private List<LocalStorySourceDto> fetchExternalPage(RegionProfile profile, int pageNo, int pageSize) {
        try {
            URI uri = UriComponentsBuilder
                    .fromHttpUrl(KTO_AREA_BASED_LIST_API)
                    .queryParam("serviceKey", serviceKey.trim())
                    .queryParam("MobileOS", "ETC")
                    .queryParam("MobileApp", "Jaramgle")
                    .queryParam("_type", "json")
                    .queryParam("arrange", "Q")
                    .queryParam("areaCode", profile.areaCode())
                    .queryParam("numOfRows", pageSize)
                    .queryParam("pageNo", pageNo)
                    .build(true)
                    .toUri();

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("KTO area API returned non-2xx status during {} sync: {}", profile.code(), response.statusCode());
                return Collections.emptyList();
            }
            return prepareSources(profile, parseAreaSources(profile, response.body(), pageSize), pageSize);
        } catch (Exception ex) {
            log.warn("Failed to fetch {} KTO area page {} during sync: {}", profile.code(), pageNo, ex.getMessage());
            return Collections.emptyList();
        }
    }

    private List<LocalStorySourceDto> parseAreaSources(RegionProfile profile, String responseBody, int limit) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception ex) {
            log.warn("Failed to parse KTO area response: {}", ex.getMessage());
            return List.of();
        }

        JsonNode body = root.path("response").path("body");
        JsonNode itemNode = body.path("items").path("item");
        List<JsonNode> rows = asRows(itemNode);
        Map<String, LocalStorySourceDto> deduped = new LinkedHashMap<>();

        for (JsonNode row : rows) {
            String title = text(row, "title");
            if (title.isBlank()) {
                continue;
            }
            String sourceId = text(row, "contentid");
            String contentTypeId = text(row, "contenttypeid");
            String address = firstNonBlank(text(row, "addr1"), text(row, "addr2"));
            String district = firstNonBlank(extractDistrictFromAddress(address), text(row, "sigungucode"));
            String imageUrl = firstNonBlank(text(row, "firstimage"), text(row, "firstimage2"));
            String thumbnailUrl = firstNonBlank(text(row, "firstimage2"), imageUrl);
            Double lat = parseDoubleOrNull(text(row, "mapy"));
            Double lng = parseDoubleOrNull(text(row, "mapx"));

            DetailInfo detail = (!detailFetchEnabled || sourceId.isBlank())
                    ? DetailInfo.empty()
                    : fetchDetailInfo(sourceId, contentTypeId);
            String intro = firstNonBlank(detail.overview(), title);
            String feature = firstNonBlank(detail.overview(), profile.storyTone());
            String storyContext = buildStoryContext(intro, feature, "", detail.overview());
            String storySeed = buildStorySeed(profile, title, district, feature, "", "", "");

            deduped.putIfAbsent(sourceId.isBlank() ? title : sourceId,
                    new LocalStorySourceDto(
                            profile.code(),
                            sourceId,
                            contentTypeId,
                            title,
                            district,
                            "",
                            intro,
                            feature,
                            "",
                            storyContext,
                            address,
                            thumbnailUrl,
                            imageUrl,
                            "",
                            "",
                            "",
                            storySeed,
                            "한국관광공사 국문 관광정보",
                            lat,
                            lng
                    ));
        }

        return deduped.values().stream().limit(limit).toList();
    }

    private DetailInfo fetchDetailInfo(String contentId, String contentTypeId) {
        try {
            URI uri = UriComponentsBuilder
                    .fromHttpUrl(KTO_DETAIL_COMMON_API)
                    .queryParam("serviceKey", serviceKey.trim())
                    .queryParam("MobileOS", "ETC")
                    .queryParam("MobileApp", "Jaramgle")
                    .queryParam("_type", "json")
                    .queryParam("contentId", contentId)
                    .queryParam("contentTypeId", contentTypeId)
                    .queryParam("defaultYN", "Y")
                    .queryParam("firstImageYN", "Y")
                    .queryParam("addrinfoYN", "Y")
                    .queryParam("mapinfoYN", "Y")
                    .queryParam("overviewYN", "Y")
                    .build(true)
                    .toUri();

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return DetailInfo.empty();
            }
            JsonNode root = objectMapper.readTree(response.body());
            List<JsonNode> rows = asRows(root.path("response").path("body").path("items").path("item"));
            if (rows.isEmpty()) {
                return DetailInfo.empty();
            }
            JsonNode first = rows.get(0);
            return new DetailInfo(cleanHtml(text(first, "overview")));
        } catch (Exception ex) {
            log.debug("Failed to fetch KTO detail for contentId={}: {}", contentId, ex.getMessage());
            return DetailInfo.empty();
        }
    }

    private List<LocalStorySourceDto> prepareSources(RegionProfile profile, List<LocalStorySourceDto> rawItems, int limit) {
        if (rawItems.isEmpty()) {
            return rawItems;
        }
        List<LocalStorySourceDto> prepared = new ArrayList<>();
        for (LocalStorySourceDto item : rawItems) {
            LocalStorySourceDto enriched = item;
            if (!hasImage(enriched) && photoEnrichmentEnabled) {
                enriched = enrichWithTourPhoto(profile, item);
            }
            prepared.add(enriched);
        }
        return prepared.stream()
                .filter(this::hasImage)
                .limit(limit)
                .toList();
    }

    private void upsertStorySource(LocalStorySourceDto item, LocalDateTime syncedAt) {
        String externalId = firstNonBlank(item.sourceId(), item.title());
        LocalStorySource source = localStorySourceRepository
                .findByRegionCodeAndExternalSourceAndExternalId(item.regionCode(), EXTERNAL_SOURCE_KTO_TOUR, externalId)
                .orElseGet(LocalStorySource::new);

        source.setRegionCode(item.regionCode());
        source.setExternalSource(EXTERNAL_SOURCE_KTO_TOUR);
        source.setExternalId(trimToLength(externalId, 120));
        source.setContentTypeId(trimToLength(item.contentTypeId(), 40));
        source.setSourceType("ATTRACTION");
        source.setTitle(trimToLength(item.title(), 255));
        source.setNormalizedTitle(trimToLength(compact(item.title()), 255));
        source.setDistrict(trimToLength(item.district(), 100));
        source.setSubtitle(item.subtitle());
        source.setIntro(item.intro());
        source.setFeature(item.feature());
        source.setOrigin(item.origin());
        source.setStoryContext(item.storyContext());
        source.setAddress(item.address());
        source.setThumbnailUrl(item.thumbnailUrl());
        source.setImageUrl(item.imageUrl());
        source.setPhotoTitle(trimToLength(item.photoTitle(), 255));
        source.setPhotoLocation(trimToLength(item.photoLocation(), 255));
        source.setPhotoKeywords(item.photoKeywords());
        source.setDataSources(item.dataSources());
        source.setLat(item.lat());
        source.setLng(item.lng());
        source.setQualityScore(score(item));
        source.setActive(hasImage(item));
        source.setLastSyncedAt(syncedAt);

        localStorySourceRepository.save(source);
    }

    private LocalStorySourceDto toDto(LocalStorySource source) {
        RegionProfile profile = profile(source.getRegionCode());
        return new LocalStorySourceDto(
                source.getRegionCode(),
                source.getExternalId(),
                source.getContentTypeId(),
                source.getTitle(),
                source.getDistrict(),
                source.getSubtitle(),
                source.getIntro(),
                source.getFeature(),
                source.getOrigin(),
                source.getStoryContext(),
                source.getAddress(),
                source.getThumbnailUrl(),
                source.getImageUrl(),
                source.getPhotoTitle(),
                source.getPhotoLocation(),
                source.getPhotoKeywords(),
                buildStorySeed(
                        profile,
                        source.getTitle(),
                        source.getDistrict(),
                        source.getFeature(),
                        source.getOrigin(),
                        source.getPhotoTitle(),
                        source.getPhotoKeywords()
                ),
                source.getDataSources(),
                source.getLat(),
                source.getLng()
        );
    }

    private LocalStorySourceDto enrichWithTourPhoto(RegionProfile profile, LocalStorySourceDto item) {
        if (!photoEnrichmentEnabled || photoApiDisabled) {
            return item;
        }
        Optional<PhotoMatch> match = findPhotoMatch(item.title(), item.district());
        if (match.isEmpty()) {
            return item;
        }
        PhotoMatch photo = match.get();
        String imageUrl = firstNonBlank(item.imageUrl(), photo.imageUrl());
        String thumbnailUrl = firstNonBlank(item.thumbnailUrl(), photo.imageUrl(), imageUrl);
        String feature = firstNonBlank(item.feature(), buildPhotoFeature(photo));
        String storyContext = buildStoryContext(
                item.intro(),
                feature,
                item.origin(),
                firstNonBlank(item.storyContext(), buildPhotoFeature(photo))
        );
        String dataSources = appendDataSource(item.dataSources(), "한국관광공사 관광사진정보");
        String storySeed = buildStorySeed(profile, item.title(), item.district(), feature, item.origin(), photo.title(), photo.keywords());

        return new LocalStorySourceDto(
                item.regionCode(),
                item.sourceId(),
                item.contentTypeId(),
                item.title(),
                item.district(),
                item.subtitle(),
                item.intro(),
                feature,
                item.origin(),
                storyContext,
                item.address(),
                thumbnailUrl,
                imageUrl,
                photo.title(),
                photo.location(),
                photo.keywords(),
                storySeed,
                dataSources,
                item.lat(),
                item.lng()
        );
    }

    private Optional<PhotoMatch> findPhotoMatch(String title, String district) {
        String effectivePhotoServiceKey = effectivePhotoServiceKey();
        if (photoApiDisabled || effectivePhotoServiceKey.isBlank()) {
            return Optional.empty();
        }
        String query = normalizePhotoQuery(title);
        if (query.isBlank()) {
            return Optional.empty();
        }
        String cacheKey = compact(query + " " + firstNonBlank(district));
        Optional<PhotoMatch> cached = photoCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            URI uri = UriComponentsBuilder
                    .fromHttpUrl(KTO_PHOTO_GALLERY_SEARCH_API_URL)
                    .queryParam("serviceKey", effectivePhotoServiceKey.trim())
                    .queryParam("MobileOS", "ETC")
                    .queryParam("MobileApp", "Jaramgle")
                    .queryParam("_type", "json")
                    .queryParam("arrange", "A")
                    .queryParam("keyword", query)
                    .queryParam("numOfRows", PHOTO_SEARCH_ROWS)
                    .queryParam("pageNo", 1)
                    .build(true)
                    .toUri();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (response.statusCode() == 401 || response.statusCode() == 403) {
                    photoApiDisabled = true;
                }
                Optional<PhotoMatch> empty = Optional.empty();
                photoCache.put(cacheKey, empty);
                return empty;
            }
            Optional<PhotoMatch> match = parsePhotoMatch(response.body(), title, district);
            photoCache.put(cacheKey, match);
            return match;
        } catch (Exception ex) {
            log.debug("Failed to fetch KTO photo for {}: {}", title, ex.getMessage());
            Optional<PhotoMatch> empty = Optional.empty();
            photoCache.put(cacheKey, empty);
            return empty;
        }
    }

    private Optional<PhotoMatch> parsePhotoMatch(String responseBody, String title, String district) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            List<JsonNode> items = asRows(root.path("response").path("body").path("items").path("item"));
            if (items.isEmpty()) {
                return Optional.empty();
            }
            PhotoMatch best = null;
            int bestScore = -1;
            for (JsonNode item : items) {
                String imageUrl = firstNonBlank(text(item, "galWebImageUrl"), text(item, "galWebImageUrl"));
                if (imageUrl.isBlank()) {
                    continue;
                }
                PhotoMatch candidate = new PhotoMatch(
                        text(item, "galTitle"),
                        text(item, "galPhotographyLocation"),
                        text(item, "galSearchKeyword"),
                        imageUrl
                );
                int score = photoMatchScore(candidate, title, district);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            return Optional.ofNullable(best);
        } catch (Exception ex) {
            log.debug("Failed to parse KTO photo response: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private int photoMatchScore(PhotoMatch photo, String title, String district) {
        String haystack = compact(String.join(" ", List.of(photo.title(), photo.location(), photo.keywords())));
        int score = 0;
        for (String token : tokenizeTitle(title)) {
            if (haystack.contains(compact(token))) {
                score += 2;
            }
        }
        if (district != null && !district.isBlank() && haystack.contains(compact(district))) {
            score += 1;
        }
        return score;
    }

    private int score(LocalStorySourceDto item) {
        int score = 0;
        if (notBlank(item.thumbnailUrl()) || notBlank(item.imageUrl())) {
            score += 30;
        }
        if (notBlank(item.photoKeywords())) {
            score += 20;
        }
        if (notBlank(item.storySeed())) {
            score += 8;
        }
        if (notBlank(item.storyContext())) {
            score += 15;
        }
        if (notBlank(item.feature())) {
            score += 10;
        }
        if (item.lat() != null && item.lng() != null) {
            score += 10;
        }
        if (notBlank(item.intro())) {
            score += 5;
        }
        return score;
    }

    private RegionProfile profile(String region) {
        String normalized = normalizeRegion(region);
        RegionProfile profile = REGION_PROFILES.get(normalized);
        if (profile == null) {
            throw new IllegalArgumentException("Unsupported local story region: " + region);
        }
        return profile;
    }

    private String normalizeRegion(String region) {
        String normalized = region == null ? "" : region.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "DAEGU", "DG", "DAEGU_METROPOLITAN" -> "DAEGU";
            case "CHUNGBUK", "CHUNGCHEONGBUK", "CHUNGCHEONGBUK_DO", "CB" -> "CHUNGBUK";
            default -> normalized;
        };
    }

    private boolean hasImage(LocalStorySourceDto item) {
        return notBlank(item.thumbnailUrl()) || notBlank(item.imageUrl());
    }

    private String buildStorySeed(
            RegionProfile profile,
            String title,
            String district,
            String feature,
            String origin,
            String photoTitle,
            String photoKeywords
    ) {
        List<String> parts = new ArrayList<>();
        if (notBlank(title)) {
            parts.add("'" + title.trim() + "'을/를 주요 배경으로 사용");
        }
        if (notBlank(district)) {
            parts.add("지역 단서: " + district.trim());
        }
        parts.add("지역 톤: " + profile.storyTone());
        if (notBlank(feature)) {
            parts.add("특징: " + trimToLength(cleanHtml(feature), 120));
        }
        if (notBlank(origin)) {
            parts.add("유래/역사: " + trimToLength(cleanHtml(origin), 120));
        }
        if (notBlank(photoTitle)) {
            parts.add("사진 장면: " + trimToLength(photoTitle.trim(), 80));
        }
        if (notBlank(photoKeywords)) {
            parts.add("시각 키워드: " + trimToLength(photoKeywords.trim(), 100));
        }
        return trimToLength(String.join(" · ", parts), 380);
    }

    private String buildPhotoFeature(PhotoMatch photo) {
        String base = firstNonBlank(photo.location(), photo.title(), photo.keywords());
        if (base.isBlank()) {
            return "";
        }
        String keywords = photo.keywords();
        if (!keywords.isBlank() && !base.contains(keywords)) {
            return base + "와 관련된 관광사진 키워드: " + keywords;
        }
        return base;
    }

    private String buildStoryContext(String intro, String feature, String origin, String rawContent) {
        String base = String.join(" ", List.of(
                intro == null ? "" : cleanHtml(intro),
                feature == null ? "" : cleanHtml(feature),
                origin == null ? "" : cleanHtml(origin)
        )).replaceAll("\\s+", " ").trim();
        if (base.isBlank()) {
            base = rawContent == null ? "" : cleanHtml(rawContent);
        }
        if (base.length() > MAX_STORY_CONTEXT_CHARS) {
            return base.substring(0, MAX_STORY_CONTEXT_CHARS).trim();
        }
        return base;
    }

    private List<JsonNode> asRows(JsonNode itemNode) {
        if (itemNode == null || itemNode.isMissingNode() || itemNode.isNull()) {
            return List.of();
        }
        if (itemNode.isArray()) {
            List<JsonNode> rows = new ArrayList<>();
            itemNode.forEach(rows::add);
            return rows;
        }
        return List.of(itemNode);
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        return value.asText("").trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private String normalizeSourceId(String sourceId) {
        if (sourceId == null) {
            return null;
        }
        String trimmed = sourceId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizePhotoQuery(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        String sanitized = title.trim();
        if (sanitized.length() > 30) {
            return sanitized.substring(0, 30).trim();
        }
        return sanitized;
    }

    private String extractDistrictFromAddress(String address) {
        if (address == null || address.isBlank()) {
            return "";
        }
        String cityFallback = "";
        for (String token : address.trim().split("\\s+")) {
            if (token.endsWith("군") || token.endsWith("구")) {
                return token;
            }
            if (cityFallback.isBlank() && token.endsWith("시")) {
                cityFallback = token;
            }
        }
        return cityFallback;
    }

    private List<String> tokenizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return List.of();
        }
        return List.of(title.split("[\\s·,/()\\[\\]-]+" )).stream()
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .filter(token -> !List.of("대구", "충북", "충청북도", "관광", "명소").contains(token))
                .toList();
    }

    private Double parseDoubleOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String compact(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s·,/()\\[\\]_-]+", "")
                .trim();
    }

    private String trimToLength(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private String cleanHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("(?i)<br\\s*/?>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String appendDataSource(String existing, String source) {
        if (source == null || source.isBlank()) {
            return existing == null ? "" : existing;
        }
        if (existing == null || existing.isBlank()) {
            return source;
        }
        if (existing.contains(source)) {
            return existing;
        }
        return existing + ", " + source;
    }

    private String effectivePhotoServiceKey() {
        return firstNonBlank(photoServiceKey, serviceKey);
    }

    public record StatusResult(
            String regionCode,
            boolean hasActiveSources,
            long activeCount,
            long visibleCount,
            long photoEnrichedCount,
            LocalDateTime latestSyncedAt
    ) {}

    public record SyncResult(
            String regionCode,
            int fetched,
            int saved,
            int skipped,
            int failedPages,
            String status
    ) {}

    public record PhotoEnrichmentResult(
            String regionCode,
            int candidates,
            int enriched,
            int skipped
    ) {}

    private record RegionProfile(String code, String displayName, String areaCode, String storyTone) {}

    private record DetailInfo(String overview) {
        static DetailInfo empty() {
            return new DetailInfo("");
        }
    }

    private record PhotoMatch(String title, String location, String keywords, String imageUrl) {}
}
