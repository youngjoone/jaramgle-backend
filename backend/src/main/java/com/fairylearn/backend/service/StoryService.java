package com.fairylearn.backend.service;

import com.fairylearn.backend.entity.Story;
import com.fairylearn.backend.dto.StoryGenerateRequest;
import com.fairylearn.backend.dto.LlmGenerateResponse;
import com.fairylearn.backend.dto.LlmStoryOutput;
import com.fairylearn.backend.dto.StoryPageDto;
import com.fairylearn.backend.entity.StoryPage;
import com.fairylearn.backend.repository.StoryRepository;
import com.fairylearn.backend.repository.StoryPageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient; // Import WebClient
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import reactor.util.retry.Retry; // Import Retry

import java.time.Duration; // Import Duration
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class StoryService {

    private final StoryRepository storyRepository;
    private final StoryPageRepository storyPageRepository;
    private final StorageQuotaService storageQuotaService;
    private final WebClient webClient; // Inject WebClient
    private final ObjectMapper objectMapper; // Inject ObjectMapper

    @Transactional(readOnly = true)
    public List<Story> getStoriesByUserId(String userId) {
        return storyRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Optional<Story> getStoryByIdAndUserId(Long storyId, String userId) {
        return storyRepository.findByIdAndUserId(storyId, userId);
    }

    @Transactional(readOnly = true)
    public List<StoryPage> getStoryPagesByStoryId(Long storyId) {
        return storyPageRepository.findByStoryIdOrderByPageNoAsc(storyId);
    }

    @Transactional
    public Story saveNewStory(String userId, String title, String ageRange, String topicsJson, String language, String lengthLevel, List<String> pageTexts) {
        // 1. Ensure slot is available
        storageQuotaService.ensureSlotAvailable(userId);

        // 2. Create and save Story
        Story story = new Story(null, userId, title, ageRange, topicsJson, language, lengthLevel, "DRAFT", LocalDateTime.now());
        story = storyRepository.save(story);

        // 3. Create and save StoryPages
        for (int i = 0; i < pageTexts.size(); i++) {
            StoryPage page = new StoryPage(null, story, i + 1, pageTexts.get(i));
            storyPageRepository.save(page);
        }

        // 4. Increase used count
        storageQuotaService.increaseUsedCount(userId);

        return story;
    }

    @Transactional
    public void deleteStory(Long storyId, String userId) {
        Optional<Story> storyOptional = storyRepository.findByIdAndUserId(storyId, userId);
        if (storyOptional.isPresent()) {
            storyRepository.delete(storyOptional.get());
            storageQuotaService.decreaseUsedCount(userId); // Decrease used count
        } else {
            throw new IllegalArgumentException("Story not found or not owned by user.");
        }
    }

    @Transactional
    public Story generateAndSaveStory(String userId, StoryGenerateRequest request) {
        // 1. Ensure slot is available
        storageQuotaService.ensureSlotAvailable(userId);

        // 2. Build prompt payload for ai-python service
        ObjectNode promptPayload = objectMapper.createObjectNode();
        promptPayload.put("age_range", request.getAgeRange());
        promptPayload.putArray("topics").addAll(request.getTopics().stream().map(s -> objectMapper.getNodeFactory().textNode(s)).collect(Collectors.toList()));
        promptPayload.putArray("objectives").addAll(request.getObjectives().stream().map(s -> objectMapper.getNodeFactory().textNode(s)).collect(Collectors.toList()));
        promptPayload.put("min_pages", request.getMinPages());
        promptPayload.put("language", request.getLanguage());
        if (request.getTitle() != null) {
            promptPayload.put("title", request.getTitle());
        }

        LlmGenerateResponse generatedResponse;
        try {
            // 3. Call ai-python service with retry logic
            generatedResponse = webClient.post()
                    .uri("/ai/generate") // Corrected endpoint
                    .bodyValue(promptPayload)
                    .retrieve()
                    .bodyToMono(LlmGenerateResponse.class)
                    .retryWhen(Retry.fixedDelay(1, Duration.ofSeconds(2)) // Retry once after 2 seconds
                            .filter(throwable -> throwable instanceof org.springframework.web.reactive.function.client.WebClientResponseException.InternalServerError) // Only retry on 5xx errors
                            .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) ->
                                    new RuntimeException("LLM generation failed after retries: " + retrySignal.failure().getMessage(), retrySignal.failure())))
                    .block(); // Block to get the result synchronously for now
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) { // Catch specific WebClientResponseException
            System.err.println("Error response from AI service: " + e.getResponseBodyAsString()); // Log response body
            throw new RuntimeException("Failed to generate story from LLM service: " + e.getStatusCode() + " " + e.getStatusText() + " from POST http://localhost:8000/ai/generate", e);
        } catch (Exception e) { // Catch generic exceptions
            // Handle LLM call failure
            throw new RuntimeException("Failed to generate story from LLM service: " + e.getMessage(), e);
        }

        // 4. Validate JSON output against schema (basic validation for now)
        if (generatedResponse == null || generatedResponse.getStory() == null || generatedResponse.getStory().getTitle() == null || generatedResponse.getStory().getPages() == null || generatedResponse.getStory().getPages().isEmpty()) {
            throw new IllegalArgumentException("Invalid LLM output: Missing title or pages.");
        }

        List<StoryPageDto> pages = generatedResponse.getStory().getPages();
        int actualPageCount = pages.size();
        int requestedMinPages = request.getMinPages();

        if (actualPageCount < requestedMinPages) {
            throw new IllegalArgumentException(String.format("Invalid LLM output: Page count mismatch. Requested minPages: %d, Actual pages: %d", requestedMinPages, actualPageCount));
        }

        // Sort pages by pageNo for consistent validation
        pages.sort(Comparator.comparing(StoryPageDto::getPageNo));

        AtomicInteger expectedPageNo = new AtomicInteger(1);
        for (StoryPageDto page : pages) {
            if (page.getPageNo() == null) {
                throw new IllegalArgumentException(String.format("Invalid LLM output: Page number is null for page %s.", page.getText()));
            }
            if (page.getPageNo() != expectedPageNo.getAndIncrement()) {
                throw new IllegalArgumentException(String.format("Invalid LLM output: Page number is not continuous or starts from 0. Expected: %d, Actual: %d", expectedPageNo.get() - 1, page.getPageNo()));
            }
            if (page.getText() == null || page.getText().isBlank()) {
                throw new IllegalArgumentException(String.format("Invalid LLM output: Page text is null or blank for page %d.", page.getPageNo()));
            }
            if (page.getText().length() < 81 || page.getText().length() > 165) {
                throw new IllegalArgumentException(String.format("Invalid LLM output: Page text length out of range for page %d. Length: %d", page.getPageNo(), page.getText().length()));
            }
        }

        // 5. Save Story and StoryPages
        Story story = new Story(
                null,
                userId,
                generatedResponse.getStory().getTitle(),
                request.getAgeRange(), // Use request's ageRange
                String.join(",", request.getTopics()), // Use request's topics
                request.getLanguage(), // Use request's language
                null, // lengthLevel is not part of generation request
                "READY", // Status after successful generation
                LocalDateTime.now()
        );
        story = storyRepository.save(story);

        for (var pageDto : generatedResponse.getStory().getPages()) {
            StoryPage page = new StoryPage(null, story, pageDto.getPageNo(), pageDto.getText());
            storyPageRepository.save(page);
        }

        // 6. Increase used count
        storageQuotaService.increaseUsedCount(userId);

        return story;
    }
}