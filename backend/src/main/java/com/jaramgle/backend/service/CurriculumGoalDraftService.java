package com.jaramgle.backend.service;

import com.jaramgle.backend.dto.curriculum.CurriculumGoalDraftRequest;
import com.jaramgle.backend.dto.curriculum.CurriculumGoalDraftResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class CurriculumGoalDraftService {

    public CurriculumGoalDraftResponse draftGoals(CurriculumGoalDraftRequest request) {
        int weeks = request.getWeeks() == null ? 2 : request.getWeeks();
        String language = normalizeLanguage(request.getBaseLanguage());
        String topic = StringUtils.hasText(request.getSubTopic()) ? request.getSubTopic().trim() : request.getCategory().trim();

        List<CurriculumGoalDraftResponse.WeekGoal> goals = new ArrayList<>();
        for (int weekNo = 1; weekNo <= weeks; weekNo++) {
            goals.add(buildGoal(language, weekNo, weeks, request.getCategory(), topic));
        }
        return new CurriculumGoalDraftResponse(goals);
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
