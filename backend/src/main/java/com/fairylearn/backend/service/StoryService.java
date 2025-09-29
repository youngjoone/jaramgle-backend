package com.fairylearn.backend.service;

import com.fairylearn.backend.dto.AiQuiz;
import com.fairylearn.backend.dto.AiStory;
import com.fairylearn.backend.dto.GenerateAudioRequestDto;
import com.fairylearn.backend.dto.StableStoryDto;
import com.fairylearn.backend.dto.StoryGenerateRequest;
import com.fairylearn.backend.entity.Story;
import com.fairylearn.backend.entity.StoryPage;
import com.fairylearn.backend.entity.StorybookPage; // Import StorybookPage
import com.fairylearn.backend.entity.Character;
import com.fairylearn.backend.repository.CharacterRepository;
import com.fairylearn.backend.repository.StoryPageRepository;
import com.fairylearn.backend.repository.StoryRepository;
import com.fairylearn.backend.service.stabilization.StoryAssembler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoryService {

    private final StoryRepository storyRepository;
    private final StoryPageRepository storyPageRepository;
    private final CharacterRepository characterRepository;
    private final StorageQuotaService storageQuotaService;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final StoryAssembler storyAssembler;

    // Helper record to return multiple values from generation
    public record GenerationResult(StableStoryDto story, JsonNode concept) {}

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
    public Story saveNewStory(String userId, String title, String ageRange, String topicsJson, String language, String lengthLevel, List<String> pageTexts, List<Long> characterIds) {
        storageQuotaService.ensureSlotAvailable(userId);
        Story story = new Story(null, userId, title, ageRange, topicsJson, language, lengthLevel, "DRAFT", LocalDateTime.now(), null, null, null, new ArrayList<StorybookPage>(), new LinkedHashSet<Character>());
        story = storyRepository.save(story);
        for (int i = 0; i < pageTexts.size(); i++) {
            StoryPage page = new StoryPage(null, story, i + 1, pageTexts.get(i));
            storyPageRepository.save(page);
        }
        assignCharacters(story, characterIds);
        storyRepository.save(story);
        story.getCharacters().size();
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
        GenerationResult generationResult = generateAiStory(request);
        return saveGeneratedStory(userId, request, generationResult.story(), generationResult.concept());
    }

    // Overloaded method for backward compatibility with StoryStreamService
    @Transactional
    public Story saveGeneratedStory(String userId, StoryGenerateRequest request, StableStoryDto stableStoryDto) {
        return saveGeneratedStory(userId, request, stableStoryDto, null);
    }

    @Transactional
    public Story saveGeneratedStory(String userId, StoryGenerateRequest request, StableStoryDto stableStoryDto, JsonNode creativeConcept) {
        String quizJson;
        String creativeConceptJson = null;
        try {
            quizJson = objectMapper.writeValueAsString(stableStoryDto.quiz());
            if (creativeConcept != null) {
                creativeConceptJson = objectMapper.writeValueAsString(creativeConcept);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize quiz or concept data.", e);
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
                creativeConceptJson, // creativeConcept
                new ArrayList<StorybookPage>(),
                new LinkedHashSet<Character>()
        );
        story = storyRepository.save(story);

        log.info("Saving {} pages for story {}: {}", stableStoryDto.pages().size(), story.getId(), stableStoryDto.pages());

        int pageNo = 1;
        for (String pageText : stableStoryDto.pages()) {
            StoryPage page = new StoryPage(null, story, pageNo++, pageText);
            storyPageRepository.save(page);
        }

        storageQuotaService.increaseUsedCount(userId);
        assignCharacters(story, request.getCharacterIds());
        storyRepository.save(story);
        story.getCharacters().size();
        return story;
    }

    private void assignCharacters(Story story, List<Long> characterIds) {
        if (characterIds == null || characterIds.isEmpty()) {
            story.getCharacters().clear();
            return;
        }
        if (characterIds.size() > 2) {
            throw new IllegalArgumentException("At most two characters can be selected");
        }
        List<Character> characters = characterRepository.findByIdIn(characterIds);
        if (characters.size() != characterIds.size()) {
            throw new IllegalArgumentException("One or more characters not found");
        }
        Map<Long, Character> characterMap = characters.stream()
                .collect(Collectors.toMap(Character::getId, character -> character));
        story.getCharacters().clear();
        for (Long characterId : characterIds) {
            Character character = characterMap.get(characterId);
            if (character != null) {
                story.getCharacters().add(character);
            }
        }
    }
    
    // Kept for backward compatibility with StoryStreamService
    public StableStoryDto generateStableStoryDto(StoryGenerateRequest request) {
        return generateAiStory(request).story();
    }

    @Cacheable(value = "story-generation", key = "#request.toString()")
    public GenerationResult generateAiStory(StoryGenerateRequest request) {
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

        if (request.getCharacterIds() != null && !request.getCharacterIds().isEmpty()) {
            List<Character> characters = characterRepository.findByIdIn(request.getCharacterIds());
            if (characters.size() != request.getCharacterIds().size()) {
                throw new IllegalArgumentException("One or more characters not found");
            }
            Map<Long, Character> characterMap = characters.stream()
                    .collect(Collectors.toMap(Character::getId, character -> character));
            ArrayNode characterArray = promptPayload.putArray("characters");
            for (Long characterId : request.getCharacterIds()) {
                Character character = characterMap.get(characterId);
                if (character != null) {
                    ObjectNode characterNode = characterArray.addObject();
                    characterNode.put("id", character.getId());
                    characterNode.put("slug", character.getSlug());
                    characterNode.put("name", character.getName());
                    characterNode.put("persona", character.getPersona());
                    characterNode.put("catchphrase", character.getCatchphrase());
                    characterNode.put("prompt_keywords", character.getPromptKeywords());
                    characterNode.put("image_path", character.getImagePath());
                }
            }
        }

        try {
            JsonNode aiResponse = webClient.post()
                    .uri("/ai/generate")
                    .bodyValue(promptPayload)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                            .filter(throwable -> throwable instanceof org.springframework.web.reactive.function.client.WebClientResponseException.ServiceUnavailable)
                            .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) ->
                                    new RuntimeException("LLM service unavailable after retries.", retrySignal.failure())))
                    .block();

            if (aiResponse == null) {
                log.warn("Received null response from LLM service, returning failsafe story.");
                return new GenerationResult(createFailsafeStory(), objectMapper.createObjectNode());
            }

            AiStory aiStory = objectMapper.treeToValue(aiResponse.get("story"), AiStory.class);
            JsonNode creativeConcept = aiResponse.get("creative_concept");

            StableStoryDto stableStory = storyAssembler.assemble(aiStory, request.getMinPages());
            return new GenerationResult(stableStory, creativeConcept);

        } catch (Exception e) {
            log.error("Failed to generate story from LLM service after retries. Returning failsafe story.", e);
            return new GenerationResult(createFailsafeStory(), objectMapper.createObjectNode());
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
        return new StableStoryDto(title, pages, List.of(quiz));
    }

    @Transactional
    public String generateAudio(Long storyId, String userId) {
        Story story = storyRepository.findByIdAndUserId(storyId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Story not found or not owned by user."));

        return generateAudioForStory(story);
    }

    @Transactional
    public String generateAudioForStory(Story story) {
        if (story.getFullAudioUrl() != null && !story.getFullAudioUrl().isEmpty()) {
            return story.getFullAudioUrl();
        }

        List<StoryPage> pages = storyPageRepository.findByStoryIdOrderByPageNoAsc(story.getId());
        story.getCharacters().size(); // initialize lazy collection

        List<GenerateAudioRequestDto.AudioPageDto> pageDtos = pages.stream()
                .map(page -> new GenerateAudioRequestDto.AudioPageDto(page.getPageNo(), page.getText()))
                .collect(Collectors.toList());

        List<GenerateAudioRequestDto.CharacterProfileDto> characterDtos = story.getCharacters().stream()
                .sorted(Comparator.comparing(Character::getId))
                .map(character -> new GenerateAudioRequestDto.CharacterProfileDto(
                        character.getId(),
                        character.getSlug(),
                        character.getName(),
                        character.getPersona(),
                        character.getCatchphrase(),
                        character.getPromptKeywords(),
                        character.getImagePath()
                ))
                .collect(Collectors.toList());

        String language = Optional.ofNullable(story.getLanguage()).orElse("KO");

        GenerateAudioRequestDto audioRequest = new GenerateAudioRequestDto(
                story.getTitle(),
                language,
                pageDtos,
                characterDtos
        );

        String audioFileName = webClient.post()
                .uri("/ai/generate-audio")
                .bodyValue(audioRequest)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        String audioUrl = "/api/audio/" + audioFileName;
        story.setFullAudioUrl(audioUrl);
        storyRepository.save(story);

        return audioUrl;
    }
}