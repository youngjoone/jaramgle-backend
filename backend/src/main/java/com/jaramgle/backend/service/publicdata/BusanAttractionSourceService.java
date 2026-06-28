package com.jaramgle.backend.service.publicdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaramgle.backend.dto.publicdata.BusanAttractionPageDto;
import com.jaramgle.backend.dto.publicdata.BusanAttractionSourceDto;
import com.jaramgle.backend.entity.BusanStorySource;
import com.jaramgle.backend.repository.BusanStorySourceRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class BusanAttractionSourceService {

    private static final String EXTERNAL_SOURCE_BUSAN_ATTRACTION = "BUSAN_ATTRACTION_API";
    private static final String BUSAN_ATTRACTION_API_URL = "https://apis.data.go.kr/6260000/AttractionService/getAttractionKr";
    private static final String KTO_PHOTO_GALLERY_SEARCH_API_URL = "https://apis.data.go.kr/B551011/PhotoGalleryService1/gallerySearchList1";
    private static final String BUSAN_IMAGE_BASE_URL = "https://www.visitbusan.net";
    private static final int DEFAULT_SIZE = 12;
    private static final int MAX_SIZE = 24;
    private static final int PHOTO_SEARCH_ROWS = 5;
    private static final int PHOTO_CACHE_MAX_SIZE = 256;
    private static final int MAX_STORY_CONTEXT_CHARS = 280;
    private static final Pattern LANGUAGE_SUFFIX_PATTERN = Pattern.compile(
            "\\s*\\((?:한|영|일|중간|중번)(?:\\s*,\\s*(?:한|영|일|중간|중번))*\\)\\s*$"
    );

    @Value("${busan.public-data.service-key:${BUSAN_PUBLIC_DATA_SERVICE_KEY:}}")
    private String serviceKey;

    @Value("${busan.public-data.photo-service-key:${KTO_PHOTO_SERVICE_KEY:${BUSAN_PUBLIC_DATA_SERVICE_KEY:}}}")
    private String photoServiceKey;

    @Value("${busan.public-data.photo-enabled:${BUSAN_PHOTO_ENRICHMENT_ENABLED:true}}")
    private boolean photoEnrichmentEnabled;

    @Value("${busan.public-data.sync-enabled:${BUSAN_PUBLIC_DATA_SYNC_ENABLED:false}}")
    private boolean scheduledSyncEnabled;

    @Value("${busan.public-data.sync-page-size:${BUSAN_PUBLIC_DATA_SYNC_PAGE_SIZE:24}}")
    private int syncPageSize;

    @Value("${busan.public-data.sync-max-pages:${BUSAN_PUBLIC_DATA_SYNC_MAX_PAGES:12}}")
    private int syncMaxPages;

    private final ObjectMapper objectMapper;
    private final BusanStorySourceRepository busanStorySourceRepository;

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

    public BusanAttractionPageDto getAttractions(int page, int size, String keyword, String sourceId) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(MAX_SIZE, size <= 0 ? DEFAULT_SIZE : size));
        String normalizedKeyword = normalizeKeyword(keyword);
        String normalizedSourceId = normalizeSourceId(sourceId);

        if (normalizedSourceId != null) {
            return findBySourceId(normalizedSourceId);
        }

        Pageable pageable = PageRequest.of(
                safePage - 1,
                safeSize,
                Sort.by(Sort.Order.desc("qualityScore"), Sort.Order.asc("title"))
        );
        Page<BusanStorySource> result = normalizedKeyword == null
                ? busanStorySourceRepository.findVisible(pageable)
                : busanStorySourceRepository.searchVisible(normalizedKeyword, pageable);

        List<BusanAttractionSourceDto> items = result.getContent().stream()
                .map(this::toDto)
                .toList();
        return new BusanAttractionPageDto(items, safePage, safeSize, result.getTotalElements(), result.hasNext());
    }

    public boolean hasActiveSources() {
        return busanStorySourceRepository.countByActiveTrue() > 0;
    }

    public StatusResult getStatus() {
        long activeCount = busanStorySourceRepository.countByActiveTrue();
        long visibleCount = busanStorySourceRepository.countVisible();
        long photoEnrichedCount = busanStorySourceRepository.countPhotoEnriched();
        LocalDateTime latestSyncedAt = busanStorySourceRepository.findLatestSyncedAt();
        return new StatusResult(
                activeCount > 0,
                activeCount,
                visibleCount,
                photoEnrichedCount,
                latestSyncedAt
        );
    }

    public SyncResult syncFromPublicData() {
        if (serviceKey == null || serviceKey.isBlank()) {
            log.warn("Busan attraction service key is not configured. Sync skipped.");
            return new SyncResult(0, 0, 0, 0, "SERVICE_KEY_MISSING");
        }

        int pageSize = Math.max(1, Math.min(MAX_SIZE, syncPageSize));
        int maxPages = Math.max(1, syncMaxPages);
        int fetched = 0;
        int saved = 0;
        int skipped = 0;
        int failedPages = 0;
        LocalDateTime syncedAt = LocalDateTime.now();

        for (int pageNo = 1; pageNo <= maxPages; pageNo++) {
            List<BusanAttractionSourceDto> pageItems = fetchExternalPage(pageNo, pageSize);
            if (pageItems.isEmpty()) {
                if (pageNo == 1) {
                    failedPages++;
                }
                break;
            }

            fetched += pageItems.size();
            for (BusanAttractionSourceDto item : pageItems) {
                if (!hasImage(item) || !hasUsableFacts(
                        item.storyContext(),
                        item.feature(),
                        item.origin(),
                        item.intro(),
                        item.photoKeywords()
                )) {
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

        SyncResult result = new SyncResult(fetched, saved, skipped, failedPages, "OK");
        log.info("Busan story source sync completed: {}", result);
        return result;
    }

    public PhotoEnrichmentResult enrichPhotoData(int maxItems) {
        int safeMax = Math.max(1, Math.min(50, maxItems <= 0 ? 12 : maxItems));
        Pageable pageable = PageRequest.of(0, safeMax, Sort.by(Sort.Order.desc("qualityScore"), Sort.Order.asc("title")));
        List<BusanStorySource> candidates = busanStorySourceRepository.findPhotoEnrichmentCandidates(pageable).getContent();
        int enriched = 0;
        int skipped = 0;

        for (BusanStorySource source : candidates) {
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
            busanStorySourceRepository.save(source);
            enriched++;
        }

        PhotoEnrichmentResult result = new PhotoEnrichmentResult(candidates.size(), enriched, skipped);
        log.info("Busan photo enrichment completed: {}", result);
        return result;
    }

    @Scheduled(cron = "${busan.public-data.sync-cron:${BUSAN_PUBLIC_DATA_SYNC_CRON:0 0 3 * * *}}")
    public void scheduledSync() {
        if (!scheduledSyncEnabled) {
            return;
        }
        syncFromPublicData();
    }

    private BusanAttractionPageDto findBySourceId(String sourceId) {
        return busanStorySourceRepository.findFirstByActiveTrueAndExternalIdOrderByQualityScoreDesc(sourceId)
                .filter(this::isStorySuitable)
                .map(source -> new BusanAttractionPageDto(List.of(toDto(source)), 1, 1, 1L, false))
                .orElseGet(() -> new BusanAttractionPageDto(List.of(), 1, 1, 0L, false));
    }

    private List<BusanAttractionSourceDto> fetchExternalPage(int pageNo, int pageSize) {
        try {
            URI uri = UriComponentsBuilder
                    .fromHttpUrl(BUSAN_ATTRACTION_API_URL)
                    .queryParam("serviceKey", serviceKey.trim())
                    .queryParam("numOfRows", pageSize)
                    .queryParam("pageNo", pageNo)
                    .queryParam("resultType", "json")
                    .build(true)
                    .toUri();

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Attraction API returned non-2xx status during sync: {}", response.statusCode());
                return Collections.emptyList();
            }
            return prepareAttractions(parseAttractions(response.body(), pageSize), pageSize);
        } catch (Exception ex) {
            log.warn("Failed to fetch attractions page {} during sync: {}", pageNo, ex.getMessage());
            return Collections.emptyList();
        }
    }

    private void upsertStorySource(BusanAttractionSourceDto item, LocalDateTime syncedAt) {
        String externalId = firstNonBlank(item.sourceId(), item.title());
        if (externalId.isBlank()) {
            return;
        }

        BusanStorySource source = busanStorySourceRepository
                .findByExternalSourceAndExternalId(EXTERNAL_SOURCE_BUSAN_ATTRACTION, externalId)
                .orElseGet(BusanStorySource::new);

        source.setExternalSource(EXTERNAL_SOURCE_BUSAN_ATTRACTION);
        source.setExternalId(externalId);
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

        busanStorySourceRepository.save(source);
    }

    private BusanAttractionSourceDto toDto(BusanStorySource source) {
        return new BusanAttractionSourceDto(
                source.getExternalId(),
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

    private List<BusanAttractionSourceDto> parseAttractions(String responseBody, int limit) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception ex) {
            log.warn("Failed to parse attraction response: {}", ex.getMessage());
            return List.of();
        }

        JsonNode serviceNode = root.path("getAttractionKr");
        String resultCode = text(serviceNode.path("header"), "code");
        if (!"00".equals(resultCode)) {
            log.warn("Attraction API returned code={}", resultCode);
            return List.of();
        }

        JsonNode itemNode = serviceNode.path("item");
        if (itemNode.isMissingNode() || itemNode.isNull()) {
            return List.of();
        }

        List<JsonNode> rows = new ArrayList<>();
        if (itemNode.isArray()) {
            itemNode.forEach(rows::add);
        } else if (itemNode.isObject()) {
            rows.add(itemNode);
        }

        Map<String, BusanAttractionSourceDto> deduped = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            if (deduped.size() >= limit) {
                break;
            }

            String title = sanitizeLanguageSuffix(text(row, "MAIN_TITLE"));
            String thumb = normalizeImageUrl(text(row, "MAIN_IMG_THUMB"));
            if (title.isBlank()) {
                continue;
            }

            String sourceId = text(row, "UC_SEQ");
            String district = text(row, "GUGUN_NM");
            String subtitle = sanitizeLanguageSuffix(text(row, "SUBTITLE"));
            String titleLine = sanitizeLanguageSuffix(text(row, "TITLE"));
            String itemContents = normalizeContent(text(row, "ITEMCNTNTS"));
            String intro = firstNonBlank(titleLine, subtitle, firstSentence(itemContents));
            String origin = extractOrigin(itemContents);
            String feature = extractFeature(itemContents, origin);
            String storyContext = buildStoryContext(intro, feature, origin, itemContents);
            String address = text(row, "ADDR1");
            String imageUrl = firstNonBlank(
                    normalizeImageUrl(text(row, "MAIN_IMG_NORMAL")),
                    thumb
            );
            thumb = firstNonBlank(thumb, imageUrl);
            Double lat = parseDoubleOrNull(text(row, "LAT"));
            Double lng = parseDoubleOrNull(text(row, "LNG"));

            deduped.putIfAbsent(sourceId.isBlank() ? title : sourceId,
                    new BusanAttractionSourceDto(
                            sourceId,
                            title,
                            district,
                            subtitle,
                            intro,
                            feature,
                            origin,
                            storyContext,
                            address,
                            thumb,
                            imageUrl,
                            "",
                            "",
                            "",
                            buildStorySeed(title, district, feature, origin, "", ""),
                            "부산광역시 관광명소정보서비스",
                            lat,
                            lng
                    ));
        }

        return deduped.values().stream().limit(limit).toList();
    }

    private List<BusanAttractionSourceDto> prepareAttractions(List<BusanAttractionSourceDto> rawItems, int limit) {
        if (rawItems == null || rawItems.isEmpty()) {
            return List.of();
        }

        List<BusanAttractionSourceDto> prepared = new ArrayList<>();
        for (BusanAttractionSourceDto item : rawItems) {
            BusanAttractionSourceDto enriched = item;
            if (!hasImage(enriched)) {
                continue;
            }
            prepared.add(enriched);
            if (prepared.size() >= limit) {
                break;
            }
        }
        return prepared;
    }

    private boolean hasImage(BusanAttractionSourceDto item) {
        return item != null
                && (notBlank(item.thumbnailUrl()) || notBlank(item.imageUrl()));
    }

    private boolean isStorySuitable(BusanStorySource source) {
        return source != null
                && hasUsableFacts(
                        source.getStoryContext(),
                        source.getFeature(),
                        source.getOrigin(),
                        source.getIntro(),
                        source.getPhotoKeywords()
                );
    }

    private boolean hasUsableFacts(String... values) {
        return notBlank(firstNonBlank(values));
    }

    private BusanAttractionSourceDto enrichWithTourPhoto(BusanAttractionSourceDto item) {
        if (item == null) {
            return null;
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

        return new BusanAttractionSourceDto(
                item.sourceId(),
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
                buildStorySeed(item.title(), item.district(), feature, item.origin(), photo.title(), photo.keywords()),
                dataSources,
                item.lat(),
                item.lng()
        );
    }

    private Optional<PhotoMatch> findPhotoMatch(String title, String district) {
        String effectivePhotoServiceKey = effectivePhotoServiceKey();
        if (!photoEnrichmentEnabled || photoApiDisabled || effectivePhotoServiceKey.isBlank()) {
            return Optional.empty();
        }

        String query = normalizePhotoQuery(title);
        if (query.isBlank()) {
            return Optional.empty();
        }

        synchronized (photoCache) {
            if (photoCache.containsKey(query)) {
                return photoCache.get(query);
            }
        }

        Optional<PhotoMatch> resolved = fetchPhotoMatch(query, title, district, effectivePhotoServiceKey);
        synchronized (photoCache) {
            photoCache.put(query, resolved);
        }
        return resolved;
    }

    private Optional<PhotoMatch> fetchPhotoMatch(String query, String title, String district, String effectivePhotoServiceKey) {
        try {
            URI uri = UriComponentsBuilder
                    .fromHttpUrl(KTO_PHOTO_GALLERY_SEARCH_API_URL)
                    .queryParam("serviceKey", effectivePhotoServiceKey.trim())
                    .queryParam("numOfRows", PHOTO_SEARCH_ROWS)
                    .queryParam("pageNo", 1)
                    .queryParam("MobileOS", "ETC")
                    .queryParam("MobileApp", "Jaramgle")
                    .queryParam("arrange", "A")
                    .queryParam("_type", "json")
                    .queryParam("keyword", query)
                    .build()
                    .encode(StandardCharsets.UTF_8)
                    .toUri();

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 403) {
                photoApiDisabled = true;
                log.warn("KTO PhotoGallery API returned 403. Photo enrichment is disabled for this runtime.");
                return Optional.empty();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("KTO PhotoGallery API returned non-2xx status: {}", response.statusCode());
                return Optional.empty();
            }
            return parsePhotoMatch(response.body(), title, district);
        } catch (Exception ex) {
            log.warn("Failed to fetch KTO photo for '{}': {}", query, ex.getMessage());
            return Optional.empty();
        }
    }

    private String effectivePhotoServiceKey() {
        return firstNonBlank(photoServiceKey, serviceKey);
    }

    private Optional<PhotoMatch> parsePhotoMatch(String responseBody, String title, String district) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception ex) {
            log.warn("Failed to parse KTO photo response: {}", ex.getMessage());
            return Optional.empty();
        }

        String resultCode = text(root.path("response").path("header"), "resultCode");
        if (!resultCode.isBlank() && !"0000".equals(resultCode)) {
            log.warn("KTO PhotoGallery API returned resultCode={}", resultCode);
            return Optional.empty();
        }

        JsonNode itemNode = root.path("response").path("body").path("items").path("item");
        if (itemNode.isMissingNode() || itemNode.isNull()) {
            return Optional.empty();
        }

        List<JsonNode> rows = new ArrayList<>();
        if (itemNode.isArray()) {
            itemNode.forEach(rows::add);
        } else if (itemNode.isObject()) {
            rows.add(itemNode);
        }

        PhotoMatch best = null;
        int bestScore = 0;
        for (JsonNode row : rows) {
            String imageUrl = normalizeExternalImageUrl(text(row, "galWebImageUrl"));
            if (imageUrl.isBlank()) {
                continue;
            }

            PhotoMatch candidate = new PhotoMatch(
                    sanitizeLanguageSuffix(text(row, "galTitle")),
                    text(row, "galPhotographyLocation"),
                    text(row, "galSearchKeyword"),
                    imageUrl
            );
            int score = scorePhotoMatch(candidate, title, district);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }

        return bestScore >= 2 ? Optional.of(best) : Optional.empty();
    }

    private int scorePhotoMatch(PhotoMatch photo, String title, String district) {
        String haystack = compact(photo.title() + " " + photo.location() + " " + photo.keywords());
        String titleNeedle = compact(title);
        String districtNeedle = compact(district);
        int score = 0;
        if (!titleNeedle.isBlank() && haystack.contains(titleNeedle)) {
            score += 4;
        }
        for (String token : tokenizePlaceTitle(title)) {
            if (haystack.contains(compact(token))) {
                score += 2;
            }
        }
        if (!districtNeedle.isBlank() && haystack.contains(districtNeedle)) {
            score += 1;
        }
        return score;
    }

    private int score(BusanAttractionSourceDto item) {
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
        if (notBlank(item.origin())) {
            score += 10;
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

    private List<String> tokenizePlaceTitle(String title) {
        if (title == null || title.isBlank()) {
            return List.of();
        }
        return List.of(title.split("[\\s·,/()\\[\\]-]+" )).stream()
                .map(this::sanitizeLanguageSuffix)
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .filter(token -> !List.of("부산", "부산광역시", "관광", "명소").contains(token))
                .toList();
    }

    private String normalizePhotoQuery(String title) {
        String sanitized = sanitizeLanguageSuffix(title);
        if (sanitized.isBlank()) {
            return "";
        }
        if (sanitized.length() > 30) {
            return sanitized.substring(0, 30).trim();
        }
        return sanitized;
    }

    private String buildPhotoFeature(PhotoMatch photo) {
        if (photo == null) {
            return "";
        }
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

    private String buildStorySeed(
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
            parts.add(district.trim() + " 지역의 분위기를 반영");
        }
        if (notBlank(feature)) {
            parts.add("특징: " + trimToLength(feature.trim(), 120));
        }
        if (notBlank(origin)) {
            parts.add("유래/역사: " + trimToLength(origin.trim(), 120));
        }
        if (notBlank(photoTitle)) {
            parts.add("사진 장면: " + trimToLength(photoTitle.trim(), 80));
        }
        if (notBlank(photoKeywords)) {
            parts.add("시각 키워드: " + trimToLength(photoKeywords.trim(), 100));
        }
        if (parts.isEmpty()) {
            return "";
        }
        String seed = String.join(" · ", parts);
        return trimToLength(seed, 360);
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

    private String normalizeImageUrl(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        if (trimmed.startsWith("/")) {
            return BUSAN_IMAGE_BASE_URL + trimmed;
        }
        return BUSAN_IMAGE_BASE_URL + "/" + trimmed;
    }

    private String normalizeExternalImageUrl(String url) {
        return url == null ? "" : url.trim();
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

    private String normalizeContent(String content) {
        if (content == null) {
            return "";
        }
        return content.replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String sanitizeLanguageSuffix(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        return LANGUAGE_SUFFIX_PATTERN.matcher(normalized).replaceAll("").trim();
    }

    private String firstSentence(String text) {
        List<String> sentences = splitSentences(text);
        return sentences.isEmpty() ? "" : sentences.get(0);
    }

    private List<String> splitSentences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.of(text.split("(?:다\\.|[.!?])\\s+" )).stream()
                .map(String::trim)
                .filter(sentence -> !sentence.isBlank())
                .toList();
    }

    private String extractOrigin(String content) {
        List<String> originKeywords = List.of("유래", "이름", "역사", "처음", "형성", "조성", "피난", "시작");
        for (String sentence : splitSentences(content)) {
            if (originKeywords.stream().anyMatch(sentence::contains)) {
                return sentence;
            }
        }
        return "";
    }

    private String extractFeature(String content, String origin) {
        List<String> featureKeywords = List.of("풍경", "전망", "체험", "골목", "바다", "문화", "대표", "명소", "예술");
        for (String sentence : splitSentences(content)) {
            if (!origin.isBlank() && sentence.equals(origin)) {
                continue;
            }
            if (featureKeywords.stream().anyMatch(sentence::contains)) {
                return sentence;
            }
        }
        for (String sentence : splitSentences(content)) {
            if (!origin.isBlank() && sentence.equals(origin)) {
                continue;
            }
            return sentence;
        }
        return "";
    }

    private String buildStoryContext(String intro, String feature, String origin, String rawContent) {
        String base = String.join(" ", List.of(
                intro == null ? "" : intro,
                feature == null ? "" : feature,
                origin == null ? "" : origin
        )).replaceAll("\\s+", " ").trim();
        if (base.isBlank()) {
            base = rawContent == null ? "" : rawContent;
        }
        if (base.length() > MAX_STORY_CONTEXT_CHARS) {
            return base.substring(0, MAX_STORY_CONTEXT_CHARS).trim();
        }
        return base;
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

    public record SyncResult(
            int fetched,
            int saved,
            int skipped,
            int failedPages,
            String status
    ) {}

    public record StatusResult(
            boolean hasActiveSources,
            long activeCount,
            long visibleCount,
            long photoEnrichedCount,
            LocalDateTime latestSyncedAt
    ) {}

    public record PhotoEnrichmentResult(
            int candidates,
            int enriched,
            int skipped
    ) {}

    private record PhotoMatch(
            String title,
            String location,
            String keywords,
            String imageUrl
    ) {}
}
