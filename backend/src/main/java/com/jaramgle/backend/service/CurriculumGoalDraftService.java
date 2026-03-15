package com.jaramgle.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaramgle.backend.dto.curriculum.CurriculumGoalDraftRequest;
import com.jaramgle.backend.dto.curriculum.CurriculumGoalDraftResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CurriculumGoalDraftService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public CurriculumGoalDraftResponse draftGoals(CurriculumGoalDraftRequest request) {
        int weeks = request.getWeeks() == null ? 2 : request.getWeeks();
        String language = normalizeLanguage(request.getBaseLanguage());
        String topic = StringUtils.hasText(request.getSubTopic()) ? request.getSubTopic().trim() : request.getCategory().trim();

        List<CurriculumGoalDraftResponse.WeekGoal> aiGoals = requestAiDraft(request, weeks, language, topic);
        if (!aiGoals.isEmpty()) {
            return new CurriculumGoalDraftResponse(aiGoals);
        }

        log.warn("Curriculum goal draft fallback used. category={}, subTopic={}, weeks={}",
                request.getCategory(), request.getSubTopic(), weeks);
        List<CurriculumGoalDraftResponse.WeekGoal> goals = new ArrayList<>();
        for (int weekNo = 1; weekNo <= weeks; weekNo++) {
            goals.add(buildGoal(language, weekNo, weeks, request.getCategory(), topic));
        }
        return new CurriculumGoalDraftResponse(goals);
    }

    private List<CurriculumGoalDraftResponse.WeekGoal> requestAiDraft(
            CurriculumGoalDraftRequest request,
            int weeks,
            String language,
            String topic
    ) {
        try {
            JsonNode payload = objectMapper.createObjectNode()
                    .put("category", request.getCategory())
                    .put("sub_topic", normalizeNullable(request.getSubTopic()))
                    .put("age_range", normalizeNullable(request.getAgeRange()))
                    .put("base_language", language)
                    .put("weeks", weeks)
                    .put("title", normalizeNullable(request.getTitle()));

            JsonNode response = webClient.post()
                    .uri("/ai/generate-curriculum-goals")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            return normalizeAiGoals(response, weeks, language, request.getCategory(), topic);
        } catch (Exception ex) {
            log.warn("AI curriculum goal draft request failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private List<CurriculumGoalDraftResponse.WeekGoal> normalizeAiGoals(
            JsonNode response,
            int weeks,
            String language,
            String category,
            String topic
    ) {
        if (response == null || !response.has("goals") || !response.get("goals").isArray()) {
            return List.of();
        }

        Map<Integer, CurriculumGoalDraftResponse.WeekGoal> byWeek = new LinkedHashMap<>();
        for (JsonNode node : response.get("goals")) {
            int weekNo = parseWeekNo(node);
            if (weekNo < 1 || weekNo > weeks || byWeek.containsKey(weekNo)) {
                continue;
            }

            String primaryGoal = firstNonBlank(
                    asText(node.get("primaryGoal")),
                    asText(node.get("primary_goal"))
            );
            if (!StringUtils.hasText(primaryGoal)) {
                continue;
            }

            List<String> subGoals = parseSubGoals(node);
            byWeek.put(weekNo, new CurriculumGoalDraftResponse.WeekGoal(
                    weekNo,
                    primaryGoal.trim(),
                    subGoals
            ));
        }

        if (byWeek.isEmpty()) {
            return List.of();
        }

        List<CurriculumGoalDraftResponse.WeekGoal> normalized = new ArrayList<>();
        for (int weekNo = 1; weekNo <= weeks; weekNo++) {
            CurriculumGoalDraftResponse.WeekGoal goal = byWeek.get(weekNo);
            if (goal == null) {
                goal = buildGoal(language, weekNo, weeks, category, topic);
            }
            normalized.add(goal);
        }

        normalized.sort(Comparator.comparing(CurriculumGoalDraftResponse.WeekGoal::getWeekNo));
        return normalized;
    }

    private int parseWeekNo(JsonNode node) {
        if (node == null || node.isNull()) {
            return -1;
        }
        JsonNode weekNoNode = node.has("weekNo") ? node.get("weekNo") : node.get("week_no");
        if (weekNoNode == null || weekNoNode.isNull()) {
            return -1;
        }
        if (weekNoNode.isInt()) {
            return weekNoNode.asInt();
        }
        try {
            return Integer.parseInt(weekNoNode.asText().trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private List<String> parseSubGoals(JsonNode node) {
        JsonNode subGoalsNode = node.has("subGoals") ? node.get("subGoals") : node.get("sub_goals");
        if (subGoalsNode == null || !subGoalsNode.isArray()) {
            return List.of();
        }
        List<String> subGoals = new ArrayList<>();
        for (JsonNode item : subGoalsNode) {
            String text = asText(item);
            if (StringUtils.hasText(text)) {
                subGoals.add(text.trim());
            }
            if (subGoals.size() == 2) {
                break;
            }
        }
        return subGoals;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String asText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeNullable(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private CurriculumGoalDraftResponse.WeekGoal buildGoal(String language, int weekNo, int totalWeeks, String category, String topic) {
        if ("KO".equals(language)) {
            return switch (weekNo) {
                case 1 -> new CurriculumGoalDraftResponse.WeekGoal(
                        weekNo,
                        String.format("%s의 핵심 개념 익히기", topic),
                        List.of("핵심 단어 2개 이해", "일상 예시 1개 연결")
                );
                case 2 -> new CurriculumGoalDraftResponse.WeekGoal(
                        weekNo,
                        String.format("%s를 생활 속 상황에 적용하기", topic),
                        List.of("이전 개념과 연결", "간단한 문제 해결 시도")
                );
                case 3 -> new CurriculumGoalDraftResponse.WeekGoal(
                        weekNo,
                        String.format("%s를 확장해 사고력 키우기", topic),
                        List.of("응용 질문 1개", "캐릭터 관계 속 선택 학습")
                );
                default -> new CurriculumGoalDraftResponse.WeekGoal(
                        weekNo,
                        String.format("%s를 스스로 설명하고 정리하기", topic),
                        List.of("주요 개념 복습", "실천 약속 1개 만들기")
                );
            };
        }

        return switch (weekNo) {
            case 1 -> new CurriculumGoalDraftResponse.WeekGoal(
                    weekNo,
                    String.format("Understand the core idea of %s", topic),
                    List.of("Learn two key terms", "Connect one daily-life example")
            );
            case 2 -> new CurriculumGoalDraftResponse.WeekGoal(
                    weekNo,
                    String.format("Apply %s in a simple situation", topic),
                    List.of("Link with last week", "Try one small problem")
            );
            case 3 -> new CurriculumGoalDraftResponse.WeekGoal(
                    weekNo,
                    String.format("Extend %s with deeper thinking", topic),
                    List.of("One extension question", "One character decision point")
            );
            default -> new CurriculumGoalDraftResponse.WeekGoal(
                    weekNo,
                    String.format("Summarize and explain %s independently", topic),
                    List.of("Review major ideas", "Set one practical habit")
            );
        };
    }

    private String normalizeLanguage(String value) {
        if (!StringUtils.hasText(value)) {
            return "KO";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (List.of("KO", "EN", "JA", "FR", "ES", "DE", "ZH").contains(normalized)) {
            return normalized;
        }
        return "KO";
    }
}
