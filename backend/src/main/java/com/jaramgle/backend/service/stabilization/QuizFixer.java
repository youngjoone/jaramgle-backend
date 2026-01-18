package com.jaramgle.backend.service.stabilization;

import com.jaramgle.backend.dto.AiQuiz;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class QuizFixer {

    public AiQuiz ensureValid(AiQuiz aiQuiz, List<String> pages) {
        if (isValid(aiQuiz)) {
            return aiQuiz;
        }
        return null;
    }

    private boolean isValid(AiQuiz quiz) {
        if (quiz == null || quiz.question() == null || quiz.question().isBlank() ||
                quiz.options() == null || quiz.options().size() < 2 ||
                quiz.answer() == null) {
            return false;
        }
        if (quiz.options().stream().anyMatch(o -> o == null || o.isBlank())) {
            return false;
        }
        return quiz.answer() >= 0 && quiz.answer() < quiz.options().size();
    }
}
