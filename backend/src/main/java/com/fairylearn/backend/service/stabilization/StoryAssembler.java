package com.fairylearn.backend.service.stabilization;

import com.fairylearn.backend.dto.AiStory;
import com.fairylearn.backend.dto.AiQuiz;
import com.fairylearn.backend.dto.StableStoryDto;
import com.fairylearn.backend.dto.StableStoryPageDto;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StoryAssembler {

    private final StoryTextExtractor textExtractor;
    private final SentenceSplitterKo sentenceSplitter;
    private final PageBuilder pageBuilder;
    private final QuizFixer quizFixer;

    public StableStoryDto assemble(AiStory aiStory, int minPages) {
        List<StableStoryPageDto> structuredPages = extractStructuredPages(aiStory);
        List<StableStoryPageDto> pagesToUse;

        boolean structuredValid = structuredPages.size() >= minPages
            && structuredPages.stream().allMatch(page -> wordCount(page.text()) >= 20);

        if (structuredValid) {
            pagesToUse = structuredPages.stream()
                    .map(page -> new StableStoryPageDto(normalizeText(page.text()), page.imagePrompt()))
                    .collect(Collectors.toList());
        } else {
            // 1. 원문 추출 및 정규화
            String rawText = textExtractor.extractRawText(aiStory);

            // 2. 문장 단위로 분리
            List<String> sentences = sentenceSplitter.split(rawText);

            // 3. 페이지 빌드
            List<String> rawPages = pageBuilder.build(sentences, minPages);
            pagesToUse = rawPages.stream()
                    .map(this::normalizeText)
                    .filter(text -> !text.isBlank())
                    .map(text -> new StableStoryPageDto(text, null))
                    .collect(Collectors.toList());
        }

        // 4. 퀴즈 검수 및 보정 (이제 리스트를 처리)
        List<AiQuiz> finalQuizzes = new ArrayList<>();
        if (aiStory.quiz() != null && !aiStory.quiz().isEmpty()) {
            // 리스트의 첫 번째 퀴즈만 검증하고 사용
            AiQuiz firstQuiz = aiStory.quiz().get(0);
            List<String> pageTexts = pagesToUse.stream().map(StableStoryPageDto::text).toList();
            AiQuiz validatedQuiz = quizFixer.ensureValid(firstQuiz, pageTexts);
            if (validatedQuiz != null) {
                finalQuizzes.add(validatedQuiz);
            }
        }
        
        // 5. 최종 결과 조합
        String title = (aiStory.title() != null && !aiStory.title().isBlank()) ? aiStory.title() : "새로운 동화";

        return new StableStoryDto(title, pagesToUse, finalQuizzes);
    }

    private List<StableStoryPageDto> extractStructuredPages(AiStory aiStory) {
        List<StableStoryPageDto> pages = new ArrayList<>();
        if (aiStory == null || aiStory.pages() == null || aiStory.pages().isNull()) {
            return pages;
        }

        var pagesNode = aiStory.pages();
        if (pagesNode.isArray()) {
            for (var pageElement : pagesNode) {
                StableStoryPageDto page = parsePageNode(pageElement);
                if (page != null && page.text() != null && !page.text().isBlank()) {
                    pages.add(page);
                }
            }
        } else if (pagesNode.isObject() && pagesNode.has("pages") && pagesNode.get("pages").isArray()) {
            for (var pageElement : pagesNode.get("pages")) {
                StableStoryPageDto page = parsePageNode(pageElement);
                if (page != null && page.text() != null && !page.text().isBlank()) {
                    pages.add(page);
                }
            }
        }
        return pages;
    }

    private StableStoryPageDto parsePageNode(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            JsonNode textNode = node.get("text");
            if (textNode != null && !textNode.isNull()) {
                String text = textNode.asText();
                String prompt = node.hasNonNull("image_prompt") ? node.get("image_prompt").asText() : null;
                return new StableStoryPageDto(text, prompt);
            }
        } else if (node.isTextual()) {
            return new StableStoryPageDto(node.asText(), null);
        }
        return null;
    }

    private int wordCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) Arrays.stream(text.trim().split("\\s+")).filter(token -> !token.isBlank()).count();
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\s\\u00A0\\u200B]+", " ").trim();
    }
}
