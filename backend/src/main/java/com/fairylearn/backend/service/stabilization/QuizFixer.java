package com.fairylearn.backend.service.stabilization;

import com.fairylearn.backend.dto.AiQuiz;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class QuizFixer {

    public AiQuiz ensureValid(AiQuiz aiQuiz, List<String> pages) {
        if (isValid(aiQuiz)) {
            return aiQuiz;
        }
        return generateRuleBasedQuiz(pages);
    }

    private boolean isValid(AiQuiz quiz) {
        if (quiz == null || quiz.question() == null || quiz.question().isBlank() ||
            quiz.options() == null || quiz.options().size() < 2 ||
            quiz.answerIndex() == null) {
            return false;
        }
        if (quiz.options().stream().anyMatch(o -> o == null || o.isBlank())) {
            return false;
        }
        return quiz.answerIndex() >= 0 && quiz.answerIndex() < quiz.options().size();
    }

    private AiQuiz generateRuleBasedQuiz(List<String> pages) {
        // Failsafe quiz
        return new AiQuiz(
            "주인공의 이름은 무엇이었을까요?",
            List.of("토끼", "거북이"),
            0
        );
    }
}
