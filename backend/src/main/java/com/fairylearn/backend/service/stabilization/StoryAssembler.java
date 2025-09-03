package com.fairylearn.backend.service.stabilization;

import com.fairylearn.backend.dto.AiStory;
import com.fairylearn.backend.dto.AiQuiz;
import com.fairylearn.backend.dto.StableStoryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StoryAssembler {

    private final StoryTextExtractor textExtractor;
    private final SentenceSplitterKo sentenceSplitter;
    private final PageBuilder pageBuilder;
    private final QuizFixer quizFixer;

    public StableStoryDto assemble(AiStory aiStory, int minPages) {
        // 1. 원문 추출 및 정규화
        String rawText = textExtractor.extractRawText(aiStory);

        // 2. 문장 단위로 분리
        List<String> sentences = sentenceSplitter.split(rawText);

        // 3. 페이지 빌드
        List<String> pages = pageBuilder.build(sentences, minPages);

        // 4. 퀴즈 검수 및 보정
        AiQuiz quiz = quizFixer.ensureValid(aiStory.quiz(), pages);
        
        // 5. 최종 결과 조합
        String title = (aiStory.title() != null && !aiStory.title().isBlank()) ? aiStory.title() : "새로운 동화";

        return new StableStoryDto(title, pages, quiz);
    }
}
