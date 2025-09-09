package com.fairylearn.backend.service;

import com.fairylearn.backend.dto.AiQuiz;
import com.fairylearn.backend.dto.AiStory;
import com.fairylearn.backend.dto.StableStoryDto;
import com.fairylearn.backend.dto.StoryGenerateRequest;
import com.fairylearn.backend.entity.Story;
import com.fairylearn.backend.entity.StoryPage;
import com.fairylearn.backend.repository.StoryPageRepository;
import com.fairylearn.backend.repository.StoryRepository;
import com.fairylearn.backend.service.stabilization.StoryAssembler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoryService {

    private final StoryRepository storyRepository;
    private final StoryPageRepository storyPageRepository;
    private final StorageQuotaService storageQuotaService;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final StoryAssembler storyAssembler;

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
        storageQuotaService.ensureSlotAvailable(userId);
        Story story = new Story(null, userId, title, ageRange, topicsJson, language, lengthLevel, "DRAFT", LocalDateTime.now(), null, null, new ArrayList<>());
        story = storyRepository.save(story);
        for (int i = 0; i < pageTexts.size(); i++) {
            StoryPage page = new StoryPage(null, story, i + 1, pageTexts.get(i));
            storyPageRepository.save(page);
        }
        storageQuotaService.increaseUsedCount(userId);
        return story;
    }

    @Transactional
    public void deleteStory(Long storyId, String userId) {
        Optional<Story> storyOptional = storyRepository.findByIdAndUserId(storyId, userId);
        if (storyOptional.isPresent()) {
            storyRepository.delete(storyOptional.get());
            storageQuotaService.decreaseUsedCount(userId);
        } else {
            throw new IllegalArgumentException("Story not found or not owned by user.");
        }
    }

    @Transactional
    public Story generateAndSaveStory(String userId, StoryGenerateRequest request) {
        storageQuotaService.ensureSlotAvailable(userId);
        StableStoryDto stableStoryDto = generateStableStoryDto(request);
        return saveGeneratedStory(userId, request, stableStoryDto);
    }

    @Transactional
    public Story saveGeneratedStory(String userId, StoryGenerateRequest request, StableStoryDto stableStoryDto) {
        String quizJson;
        try {
            quizJson = objectMapper.writeValueAsString(stableStoryDto.quiz());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize quiz data.", e);
        }

        Story story = new Story(
                null,
                userId,
                stableStoryDto.title(),
                request.getAgeRange(),
                String.join(",", request.getTopics()),
                request.getLanguage(),
                null, // lengthLevel
                "READY",
                LocalDateTime.now(),
                quizJson,
                null, // fullAudioUrl
                new ArrayList<>()
        );
        story = storyRepository.save(story);

        log.info("Saving {} pages for story {}: {}", stableStoryDto.pages().size(), story.getId(), stableStoryDto.pages()); // Add this log

        int pageNo = 1;
        for (String pageText : stableStoryDto.pages()) {
            StoryPage page = new StoryPage(null, story, pageNo++, pageText);
            storyPageRepository.save(page);
        }

        storageQuotaService.increaseUsedCount(userId);
        return story;
    }


    @Cacheable(value = "story-generation", key = "#request.toString()")
    public StableStoryDto generateStableStoryDto(StoryGenerateRequest request) {
        log.info("Cache miss. Generating new story for request: {}", request);
        ObjectNode promptPayload = objectMapper.createObjectNode();
        promptPayload.put("age_range", request.getAgeRange());
        promptPayload.putArray("topics").addAll(request.getTopics().stream().map(objectMapper.getNodeFactory()::textNode).collect(Collectors.toList()));
        promptPayload.putArray("objectives").addAll(request.getObjectives().stream().map(objectMapper.getNodeFactory()::textNode).collect(Collectors.toList()));
        promptPayload.put("min_pages", request.getMinPages());
        promptPayload.put("language", request.getLanguage());
        if (request.getTitle() != null) {
            promptPayload.put("title", request.getTitle());
        }

        try {
            AiStory aiStory = webClient.post()
                    .uri("/ai/generate")
                    .bodyValue(promptPayload)
                    .retrieve()
                    .bodyToMono(AiStory.class)
                    .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                            .filter(throwable -> throwable instanceof org.springframework.web.reactive.function.client.WebClientResponseException.ServiceUnavailable)
                            .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) ->
                                    new RuntimeException("LLM service unavailable after retries.", retrySignal.failure())))
                    .block();

            if (aiStory == null) {
                log.warn("Received null response from LLM service, returning failsafe story.");
                return createFailsafeStory();
            }

            return storyAssembler.assemble(aiStory, request.getMinPages());

        } catch (Exception e) {
            log.error("Failed to generate story from LLM service after retries. Returning failsafe story.", e);
            return createFailsafeStory();
        }
    }

    private StableStoryDto createFailsafeStory() {
        String title = "용감한 토끼의 모험";
        List<String> pages = List.of(
                "깊은 숲 속에 용감한 토끼 ‘토토’가 살고 있었어요. 토토는 친구들과 함께 숲을 탐험하는 것을 아주 좋아했답니다. 매일 새로운 길을 찾아 나서는 용감한 탐험가였죠.",
                "어느 날, 숲 속에서 가장 지혜로운 동물로 알려진 늙은 부엉이가 토토를 불렀어요. 부엉이는 토토에게 숲의 평화를 위협하는 심술궂은 여우에 대해 이야기해주었습니다.",
                "토토는 친구들을 지키기 위해 용기를 내기로 결심했어요. 토토는 함정을 만들어 심술궂은 여우를 골탕 먹이기로 했죠. 친구들과 함께 열심히 함정을 준비했습니다.",
                "결국 토토와 친구들은 힘을 합쳐 여우를 잡았고, 여우는 다시는 친구들을 괴롭히지 않겠다고 약속했어요. 숲에는 다시 평화가 찾아왔고 모두가 토토를 칭찬했답니다."
        );
        AiQuiz quiz = new AiQuiz("토끼의 이름은 무엇이었나요?", List.of("토토", "코코", "모모"), 0);
        return new StableStoryDto(title, pages, quiz);
    }

    @Transactional
    public String generateAudio(Long storyId, String userId) {
        Story story = storyRepository.findByIdAndUserId(storyId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Story not found or not owned by user."));

        if (story.getFullAudioUrl() != null && !story.getFullAudioUrl().isEmpty()) {
            return story.getFullAudioUrl();
        }

        List<StoryPage> pages = storyPageRepository.findByStoryIdOrderByPageNoAsc(storyId);
        String fullText = pages.stream()
                .map(StoryPage::getText)
                .collect(Collectors.joining("\n\n"));

        // Call Python AI service to generate audio
        String audioFileName = webClient.post()
                .uri("/ai/generate-tts") // Assuming this is the endpoint in the Python service
                .bodyValue(fullText)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        String audioUrl = "/api/audio/" + audioFileName;
        story.setFullAudioUrl(audioUrl);
        storyRepository.save(story);

        return audioUrl;
    }
}