package com.fairylearn.backend.service.stabilization;

import com.fairylearn.backend.dto.AiQuiz;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class QuizFixerTest {

    private QuizFixer quizFixer;
    private final List<String> dummyPages = List.of("이것은 본문입니다.");

    @BeforeEach
    void setUp() {
        quizFixer = new QuizFixer();
    }

    @Test
    @DisplayName("유효한 퀴즈가 들어오면 그대로 반환한다")
    void testWithValidQuiz() {
        AiQuiz validQuiz = new AiQuiz("질문?", List.of("답1", "답2"), 0);
        AiQuiz result = quizFixer.ensureValid(validQuiz, dummyPages);

        assertThat(result).isSameAs(validQuiz);
    }

    @Test
    @DisplayName("퀴즈가 null이면 규칙 기반 퀴즈를 생성한다")
    void testWithNullQuiz() {
        AiQuiz result = quizFixer.ensureValid(null, dummyPages);

        assertThat(result).isNotNull();
        assertThat(result.question()).isEqualTo("주인공의 이름은 무엇이었을까요?");
    }

    @Test
    @DisplayName("선택지가 1개인 퀴즈는 유효하지 않으므로 새로 생성한다")
    void testWithNotEnoughOptions() {
        AiQuiz invalidQuiz = new AiQuiz("질문?", List.of("답1"), 0);
        AiQuiz result = quizFixer.ensureValid(invalidQuiz, dummyPages);

        assertThat(result).isNotNull();
        assertThat(result).isNotSameAs(invalidQuiz);
        assertThat(result.options()).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("정답 인덱스가 범위를 벗어나면 새로 생성한다")
    void testWithInvalidAnswerIndex() {
        AiQuiz invalidQuiz = new AiQuiz("질문?", List.of("답1", "답2"), 2); // Index 2 is out of bounds
        AiQuiz result = quizFixer.ensureValid(invalidQuiz, dummyPages);

        assertThat(result).isNotNull();
        assertThat(result).isNotSameAs(invalidQuiz);
        assertThat(result.answerIndex()).isBetween(0, result.options().size() - 1);
    }
    
    @Test
    @DisplayName("질문이 비어있으면 새로 생성한다")
    void testWithBlankQuestion() {
        AiQuiz invalidQuiz = new AiQuiz(" ", List.of("답1", "답2"), 0);
        AiQuiz result = quizFixer.ensureValid(invalidQuiz, dummyPages);

        assertThat(result).isNotNull();
        assertThat(result).isNotSameAs(invalidQuiz);
        assertThat(result.question()).isNotBlank();
    }
}
