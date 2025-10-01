package com.fairylearn.backend.service;

import com.fairylearn.backend.dto.GenerateImageRequestDto;
import com.fairylearn.backend.dto.GenerateImageResponseDto;
import com.fairylearn.backend.entity.Story;
import com.fairylearn.backend.entity.StoryPage;
import com.fairylearn.backend.entity.StorybookPage;
import com.fairylearn.backend.repository.StoryRepository;
import com.fairylearn.backend.repository.StorybookPageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@RequiredArgsConstructor
@Slf4j
public class StorybookService {

    private final StoryRepository storyRepository;
    private final StorybookPageRepository storybookPageRepository;
    private final StoryService storyService;
    private final WebClient webClient;
    private final ObjectMapper objectMapper; // ADDED

    @Transactional
    public StorybookPage createStorybook(Long storyId) {
        List<StorybookPage> existingPages = storybookPageRepository.findByStoryIdOrderByPageNumberAsc(storyId);
        if (!existingPages.isEmpty()) {
            log.info("Storybook pages already exist for storyId: {}. Returning first page.", storyId);
            return existingPages.get(0);
        }

        Story story = storyRepository.findById(storyId)
                .orElseThrow(() -> new IllegalArgumentException("Story not found"));
        story.getCharacters().size();

        List<StoryPage> originalPages = storyService.getStoryPagesByStoryId(storyId);
        if (originalPages.isEmpty()) {
            throw new IllegalStateException("Story has no pages to create a storybook from.");
        }

        StoryPage firstOriginalPage = originalPages.get(0);
        StorybookPage firstStorybookPage = generateAndSaveStorybookPage(story, 1, firstOriginalPage.getText());
        log.info("createStorybook: First page generated and saved. ID: {}", firstStorybookPage.getId());

        if (originalPages.size() > 1) {
            List<String> remainingTexts = originalPages.subList(1, originalPages.size()).stream()
                    .map(StoryPage::getText)
                    .collect(Collectors.toList());
            generateRemainingImages(story.getId(), remainingTexts);
        }

        return firstStorybookPage;
    }

    @Transactional(readOnly = true)
    public List<StorybookPage> getStorybookPages(Long storyId) {
        log.info("Fetching storybook pages for storyId: {}", storyId);
        List<StorybookPage> pages = storybookPageRepository.findByStoryIdOrderByPageNumberAsc(storyId);
        log.info("Found {} storybook pages for storyId: {}", pages.size(), storyId);
        return pages;
    }

    @Async
    @Transactional
    public void generateRemainingImages(Long storyId, List<String> remainingPageTexts) {
        log.info("Starting async generation for {} remaining pages for storyId: {}", remainingPageTexts.size(), storyId);
        Story story = storyRepository.findById(storyId)
                .orElseThrow(() -> new IllegalArgumentException("Story not found"));
        story.getCharacters().size();
        for (int i = 0; i < remainingPageTexts.size(); i++) {
            int pageNumber = i + 2;
            try {
                StorybookPage generatedPage = generateAndSaveStorybookPage(story, pageNumber, remainingPageTexts.get(i));
                log.info("Async: Generated and saved page {} for storyId: {}. ID: {}", pageNumber, story.getId(), generatedPage.getId());
            } catch (Exception e) {
                log.error("Async: Failed to generate image for storyId: {}, pageNumber: {}. Error: {}",
                        storyId, pageNumber, e.getMessage());
            }
        }
        log.info("Finished async generation for storyId: {}", storyId);
    }

    private StorybookPage generateAndSaveStorybookPage(Story story, int pageNumber, String text) {
        log.info("Generating image for storyId: {}, pageNumber: {}", story.getId(), pageNumber);

        String artStyle = "A minimalistic watercolor style with a soft pastel palette."; // Default
        List<GenerateImageRequestDto.CharacterVisualDto> characterVisuals = new ArrayList<>();

        String conceptJson = story.getCreativeConcept();
        if (conceptJson != null && !conceptJson.isEmpty()) {
            try {
                JsonNode conceptNode = objectMapper.readTree(conceptJson);
                artStyle = conceptNode.path("art_style").asText(artStyle);
                JsonNode sheetsNode = conceptNode.path("character_sheets");
                if (sheetsNode.isArray()) {
                    characterVisuals = StreamSupport.stream(sheetsNode.spliterator(), false)
                            .map(node -> new GenerateImageRequestDto.CharacterVisualDto(
                                    node.path("name").asText(),
                                    node.path("visual_description").asText()
                            ))
                            .collect(Collectors.toList());
                }
            } catch (IOException e) {
                log.error("Failed to parse creative_concept JSON for storyId: {}. Using defaults.", story.getId(), e);
            }
        }

        GenerateImageRequestDto requestDto = new GenerateImageRequestDto(
                text,
                Collections.emptyList(),
                artStyle,
                characterVisuals
        );

        GenerateImageResponseDto responseDto = webClient.post()
                .uri("/ai/generate-image")
                .bodyValue(requestDto)
                .retrieve()
                .bodyToMono(GenerateImageResponseDto.class)
                .block();

        if (responseDto == null || responseDto.getFilePath() == null) {
            throw new RuntimeException("Failed to get image path from AI service.");
        }

        String webAccessibleUrl = String.format("http://localhost:8080/images/%s", responseDto.getFilePath());

        StorybookPage storybookPage = new StorybookPage();
        storybookPage.setStory(story);
        storybookPage.setPageNumber(pageNumber);
        storybookPage.setText(text);
        storybookPage.setImageUrl(webAccessibleUrl);

        StorybookPage savedPage = storybookPageRepository.save(storybookPage);
        log.info("Saved storybook page {} for storyId: {}. Image URL: {}", pageNumber, story.getId(), webAccessibleUrl);
        return savedPage;
    }
}
