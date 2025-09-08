package com.fairylearn.backend.service;

import com.fairylearn.backend.dto.GenerateImageRequestDto;
import com.fairylearn.backend.dto.GenerateImageResponseDto;
import com.fairylearn.backend.entity.Story;
import com.fairylearn.backend.entity.StoryPage;
import com.fairylearn.backend.entity.StorybookPage;
import com.fairylearn.backend.repository.StoryRepository;
import com.fairylearn.backend.repository.StorybookPageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StorybookService {

    private final StoryRepository storyRepository;
    private final StorybookPageRepository storybookPageRepository;
    private final StoryService storyService;
    private final WebClient webClient;

    @Transactional
    public StorybookPage createStorybook(Long storyId) {
        // Check if storybook pages already exist for this story
        List<StorybookPage> existingPages = storybookPageRepository.findByStoryIdOrderByPageNumberAsc(storyId);
        if (!existingPages.isEmpty()) {
            log.info("Storybook pages already exist for storyId: {}. Returning first page.", storyId);
            return existingPages.get(0);
        }

        Story story = storyRepository.findById(storyId)
                .orElseThrow(() -> new IllegalArgumentException("Story not found"));

        List<StoryPage> originalPages = storyService.getStoryPagesByStoryId(storyId);
        if (originalPages.isEmpty()) {
            throw new IllegalStateException("Story has no pages to create a storybook from.");
        }

        // Synchronously generate the first page
        StoryPage firstOriginalPage = originalPages.get(0);
        StorybookPage firstStorybookPage = generateAndSaveStorybookPage(story, 1, firstOriginalPage.getText());

        // Asynchronously generate the rest
        if (originalPages.size() > 1) {
            List<StoryPage> remainingPages = originalPages.subList(1, originalPages.size());
            generateRemainingImages(story, remainingPages);
        }

        return firstStorybookPage;
    }

    @Transactional(readOnly = true)
    public List<StorybookPage> getStorybookPages(Long storyId) {
        return storybookPageRepository.findByStoryIdOrderByPageNumberAsc(storyId);
    }

    @Async
    @Transactional
    public void generateRemainingImages(Story story, List<StoryPage> remainingPages) {
        log.info("Starting async generation for {} remaining pages for storyId: {}", remainingPages.size(), story.getId());
        for (int i = 0; i < remainingPages.size(); i++) {
            StoryPage originalPage = remainingPages.get(i);
            int pageNumber = i + 2; // Starts from page 2
            try {
                generateAndSaveStorybookPage(story, pageNumber, originalPage.getText());
            } catch (Exception e) {
                log.error("Failed to generate image for storyId: {}, pageNumber: {}. Error: {}", 
                        story.getId(), pageNumber, e.getMessage());
                // In a real application, you might want to set an error state for this page
            }
        }
        log.info("Finished async generation for storyId: {}", story.getId());
    }

    private StorybookPage generateAndSaveStorybookPage(Story story, int pageNumber, String text) {
        log.info("Generating image for storyId: {}, pageNumber: {}", story.getId(), pageNumber);
        GenerateImageRequestDto requestDto = new GenerateImageRequestDto(text);
        
        GenerateImageResponseDto responseDto = webClient.post()
                .uri("/ai/generate-image")
                .bodyValue(requestDto)
                .retrieve()
                .bodyToMono(GenerateImageResponseDto.class)
                .block(); // Making a blocking call

        if (responseDto == null || responseDto.getFilePath() == null) {
            throw new RuntimeException("Failed to get image path from AI service.");
        }

        // Construct the web-accessible URL
        String webAccessibleUrl = String.format("http://localhost:8080/images/%s", responseDto.getFilePath());

        StorybookPage storybookPage = new StorybookPage();
        storybookPage.setStory(story);
        storybookPage.setPageNumber(pageNumber);
        storybookPage.setText(text);
        storybookPage.setImageUrl(webAccessibleUrl);

        return storybookPageRepository.save(storybookPage);
    }
}
