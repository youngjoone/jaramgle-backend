package com.jaramgle.backend.service.publicdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaramgle.backend.dto.publicdata.BusanAttractionPageDto;
import com.jaramgle.backend.dto.publicdata.BusanAttractionSourceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class BusanAttractionSourceService {

    private static final String BUSAN_ATTRACTION_API_URL = "https://apis.data.go.kr/6260000/AttractionService/getAttractionKr";
    private static final String BUSAN_IMAGE_BASE_URL = "https://www.visitbusan.net";
    private static final int DEFAULT_SIZE = 12;
    private static final int MAX_SIZE = 24;
    private static final int SEARCH_FETCH_PAGE_SIZE = 30;
    private static final int SEARCH_FETCH_MAX_PAGES = 4;
    private static final int MAX_STORY_CONTEXT_CHARS = 280;
    // 예: "국립해양박물관(한,영,중간,중번,일)" 같은 언어 표기 접미사 제거
    private static final Pattern LANGUAGE_SUFFIX_PATTERN = Pattern.compile(
            "\\s*\\((?:한|영|일|중간|중번)(?:\\s*,\\s*(?:한|영|일|중간|중번))*\\)\\s*$"
    );

    @Value("${busan.public-data.service-key:${BUSAN_PUBLIC_DATA_SERVICE_KEY:}}")
    private String serviceKey;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    public BusanAttractionPageDto getAttractions(int page, int size, String keyword, String sourceId) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(MAX_SIZE, size <= 0 ? DEFAULT_SIZE : size));
        String normalizedKeyword = normalizeKeyword(keyword);
        String normalizedSourceId = normalizeSourceId(sourceId);
        if (serviceKey == null || serviceKey.isBlank()) {
            log.warn("Busan attraction service key is not configured.");
            return new BusanAttractionPageDto(List.of(), safePage, safeSize, 0L, false);
        }

        if (normalizedSourceId != null) {
            return findBySourceId(normalizedSourceId);
        }

        if (normalizedKeyword != null) {
            return searchAttractions(safePage, safeSize, normalizedKeyword);
        }

        try {
            URI uri = UriComponentsBuilder
                    .fromHttpUrl(BUSAN_ATTRACTION_API_URL)
                    .queryParam("serviceKey", serviceKey.trim())
                    .queryParam("numOfRows", safeSize)
                    .queryParam("pageNo", safePage)
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
                log.warn("Attraction API returned non-2xx status: {}", response.statusCode());
                return new BusanAttractionPageDto(List.of(), safePage, safeSize, 0L, false);
            }

            List<BusanAttractionSourceDto> parsed = parseAttractions(response.body(), safeSize);
            boolean hasNext = parsed.size() >= safeSize;
            return new BusanAttractionPageDto(parsed, safePage, safeSize, null, hasNext);
        } catch (Exception ex) {
            log.warn("Failed to fetch Busan attractions: {}", ex.getMessage());
            return new BusanAttractionPageDto(List.of(), safePage, safeSize, 0L, false);
        }
    }

    private BusanAttractionPageDto findBySourceId(String sourceId) {
        try {
            URI uri = UriComponentsBuilder
                    .fromHttpUrl(BUSAN_ATTRACTION_API_URL)
                    .queryParam("serviceKey", serviceKey.trim())
                    .queryParam("numOfRows", 1)
                    .queryParam("pageNo", 1)
                    .queryParam("UC_SEQ", sourceId)
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
                return new BusanAttractionPageDto(List.of(), 1, 1, 0L, false);
            }
            List<BusanAttractionSourceDto> items = parseAttractions(response.body(), 1);
            if (items.isEmpty()) {
                return new BusanAttractionPageDto(List.of(), 1, 1, 0L, false);
            }
            return new BusanAttractionPageDto(items, 1, 1, 1L, false);
        } catch (Exception ex) {
            log.warn("Failed to fetch attraction by sourceId {}: {}", sourceId, ex.getMessage());
            return new BusanAttractionPageDto(List.of(), 1, 1, 0L, false);
        }
    }

    private BusanAttractionPageDto searchAttractions(int page, int size, String keyword) {
        List<BusanAttractionSourceDto> collected = new ArrayList<>();

        for (int apiPage = 1; apiPage <= SEARCH_FETCH_MAX_PAGES; apiPage++) {
            List<BusanAttractionSourceDto> batch = fetchPage(apiPage, SEARCH_FETCH_PAGE_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            collected.addAll(batch);
            if (batch.size() < SEARCH_FETCH_PAGE_SIZE) {
                break;
            }
        }

        if (collected.isEmpty()) {
            return new BusanAttractionPageDto(List.of(), page, size, 0L, false);
        }

        List<BusanAttractionSourceDto> filtered = collected.stream()
                .filter(item -> containsKeyword(item, keyword))
                .collect(Collectors.toList());

        int from = Math.max(0, (page - 1) * size);
        if (from >= filtered.size()) {
            return new BusanAttractionPageDto(List.of(), page, size, (long) filtered.size(), false);
        }
        int to = Math.min(filtered.size(), from + size);
        boolean hasNext = to < filtered.size();
        return new BusanAttractionPageDto(
                filtered.subList(from, to),
                page,
                size,
                (long) filtered.size(),
                hasNext
        );
    }

    private List<BusanAttractionSourceDto> fetchPage(int pageNo, int pageSize) {
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
                return Collections.emptyList();
            }
            return parseAttractions(response.body(), pageSize);
        } catch (Exception ex) {
            log.warn("Failed to fetch attractions page {}: {}", pageNo, ex.getMessage());
            return Collections.emptyList();
        }
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
                            lat,
                            lng
                    ));
        }

        return deduped.values().stream().limit(limit).toList();
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

    private boolean containsKeyword(BusanAttractionSourceDto item, String keyword) {
        return contains(item.title(), keyword)
                || contains(item.district(), keyword)
                || contains(item.subtitle(), keyword)
                || contains(item.intro(), keyword)
                || contains(item.feature(), keyword)
                || contains(item.origin(), keyword)
                || contains(item.storyContext(), keyword)
                || contains(item.address(), keyword)
                ;
    }

    private boolean contains(String source, String keyword) {
        if (source == null || source.isBlank() || keyword == null || keyword.isBlank()) {
            return false;
        }
        return source.toLowerCase(Locale.ROOT).contains(keyword);
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
        return List.of(text.split("(?:다\\.|[.!?])\\s+")).stream()
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
}
