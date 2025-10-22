package com.fairylearn.backend.service;

import com.fairylearn.backend.dto.AiQuiz;
import com.fairylearn.backend.dto.AiStory;
import com.fairylearn.backend.dto.GenerateAudioFromStoryRequestDto;

import com.fairylearn.backend.dto.StableStoryDto;
import com.fairylearn.backend.dto.StableStoryPageDto;
import com.fairylearn.backend.dto.StoryGenerateRequest;
import com.fairylearn.backend.entity.Story;
import com.fairylearn.backend.entity.StoryPage;
import com.fairylearn.backend.entity.StorybookPage; // Import StorybookPage
import com.fairylearn.backend.entity.Character;
import com.fairylearn.backend.entity.CharacterModelingStatus;
import com.fairylearn.backend.entity.CharacterScope;
import com.fairylearn.backend.repository.CharacterRepository;
import com.fairylearn.backend.repository.StoryPageRepository;
import com.fairylearn.backend.repository.StoryRepository;
import com.fairylearn.backend.repository.StorybookPageRepository;
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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoryService {

    private final StoryRepository storyRepository;
    private final StoryPageRepository storyPageRepository;
    private final StorybookPageRepository storybookPageRepository;
    private final CharacterRepository characterRepository;
    private final StorageQuotaService storageQuotaService;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final StoryAssembler storyAssembler;
    private final CharacterModelingService characterModelingService;

    private static final String CHARACTER_IMAGE_DIR =
            System.getenv().getOrDefault("CHARACTER_IMAGE_DIR", "/Users/kyj/testchardir");

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
        Story story = new Story(null, userId, title, ageRange, topicsJson, language, lengthLevel, "DRAFT", LocalDateTime.now(), null, null, new ArrayList<StorybookPage>(), new LinkedHashSet<Character>());
        story = storyRepository.save(story);
        for (int i = 0; i < pageTexts.size(); i++) {
            StoryPage page = new StoryPage(null, story, i + 1, pageTexts.get(i), null, null, null);
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
            Story story = storyOptional.get();

            // Delete associated files
            List<StoryPage> storyPages = storyPageRepository.findByStoryIdOrderByPageNoAsc(storyId);
            for (StoryPage page : storyPages) {
                deleteFileFromUrl(page.getImageUrl());
                deleteFileFromUrl(page.getAudioUrl());
            }

            List<StorybookPage> storybookPages = storybookPageRepository.findByStoryIdOrderByPageNumberAsc(storyId);
            for (StorybookPage page : storybookPages) {
                deleteFileFromUrl(page.getImageUrl());
                deleteFileFromUrl(page.getAudioUrl());
            }

            storyRepository.delete(story);
            storageQuotaService.decreaseUsedCount(userId);
        } else {
            throw new IllegalArgumentException("Story not found or not owned by user.");
        }
    }

    private void deleteFileFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }

        try {
            String path;
            if (url.startsWith("http")) {
                path = new java.net.URL(url).getPath();
            } else {
                path = url;
            }
            
            java.io.File fileToDelete = null;
            if (path.contains("/api/image/")) {
                String filename = path.substring(path.lastIndexOf('/') + 1);
                fileToDelete = new java.io.File("/Users/kyj/testimagedir", filename);
            } else if (path.contains("/api/audio/")) {
                String relativePath = path.substring("/api/audio/".length());
                fileToDelete = new java.io.File("/Users/kyj/testaudiodir", relativePath);
            }

            if (fileToDelete != null && fileToDelete.exists()) {
                if (fileToDelete.delete()) {
                    log.info("Deleted file: {}", fileToDelete.getAbsolutePath());
                } else {
                    log.warn("Failed to delete file: {}", fileToDelete.getAbsolutePath());
                }
            }
        } catch (java.net.MalformedURLException e) {
            log.warn("Invalid URL, cannot delete file: {}", url);
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
                creativeConceptJson, // creativeConcept
                new ArrayList<StorybookPage>(),
                new LinkedHashSet<Character>()
        );
        story = storyRepository.save(story);

        log.info("Saving {} pages for story {}: {}", stableStoryDto.pages().size(), story.getId(), stableStoryDto.pages());

        int pageNo = 1;
        for (StableStoryPageDto pageDto : stableStoryDto.pages()) {
            StoryPage page = new StoryPage(null, story, pageNo++, pageDto.text(), pageDto.imagePrompt(), null, null);
            storyPageRepository.save(page);
        }

        storageQuotaService.increaseUsedCount(userId);
        assignCharacters(story, request.getCharacterIds());
        storyRepository.save(story);
        if (creativeConcept != null && !creativeConcept.isNull()) {
            applyCharacterMetadata(story, request, creativeConcept);
        }
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
        if (request.getMoral() != null && !request.getMoral().isBlank()) {
            promptPayload.put("moral", request.getMoral().trim());
        }
        if (request.getRequiredElements() != null && !request.getRequiredElements().isEmpty()) {
            ArrayNode requiredArray = promptPayload.putArray("required_elements");
            request.getRequiredElements().stream()
                    .map(element -> element == null ? "" : element.trim())
                    .filter(element -> !element.isEmpty())
                    .forEach(requiredArray::add);
        }
        if (request.getArtStyle() != null && !request.getArtStyle().isBlank()) {
            promptPayload.put("art_style", request.getArtStyle().trim());
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
                    characterNode.put("image_url", character.getImageUrl());
                    characterNode.put("visual_description", character.getVisualDescription());
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
            return new GenerationResult(stableStory, creativeConcept); // 결과에 reading_plan 추가

        } catch (Exception e) {
            log.error("Failed to generate story from LLM service after retries. Returning failsafe story.", e);
            return new GenerationResult(createFailsafeStory(), objectMapper.createObjectNode());
        }
    }

    private void applyCharacterMetadata(Story story, StoryGenerateRequest request, JsonNode creativeConcept) {
        if (creativeConcept == null || creativeConcept.isNull()) {
            return;
        }

        Map<String, Character> storyCharactersBySlug = story.getCharacters().stream()
                .filter(character -> character.getSlug() != null && !character.getSlug().isBlank())
                .collect(Collectors.toMap(
                        character -> character.getSlug().toLowerCase(Locale.ROOT),
                        Function.identity(),
                        (existing, duplicate) -> existing
                ));

        if (request.getCharacterIds() != null && !request.getCharacterIds().isEmpty()) {
            List<Character> selectedCharacters = characterRepository.findByIdIn(request.getCharacterIds());
            for (Character character : selectedCharacters) {
                storyCharactersBySlug.putIfAbsent(character.getSlug().toLowerCase(Locale.ROOT), character);
            }
        }

        JsonNode characterSheets = creativeConcept.path("character_sheets");
        if (!characterSheets.isArray()) {
            return;
        }

        Set<Character> charactersToPersist = new HashSet<>();
        characterSheets.forEach(node -> {
            String name = node.path("name").asText(null);
            if (name == null || name.isBlank()) {
                return;
            }

            String requestedSlug = node.path("slug").asText(null);
            String slug = generateSlug(requestedSlug, name);
            String normalizedSlug = slug.toLowerCase(Locale.ROOT);

            Character character = storyCharactersBySlug.get(normalizedSlug);
            if (character == null) {
                character = characterRepository.findBySlug(slug).orElse(null);
                if (character == null) {
                    character = new Character();
                    character.setSlug(slug);
                    character.setName(name);
                    character.setPersona(node.path("persona").asText(null));
                    character.setPromptKeywords(node.path("prompt_keywords").asText(null));
                    character.setCatchphrase(node.path("catchphrase").asText(null));
                    character.setVisualDescription(node.path("visual_description").asText(""));
                    character.setScope(CharacterScope.STORY);
                    String sheetImage = node.path("image_url").asText(null);
                    if (sheetImage != null && !sheetImage.isBlank()) {
                        character.setImageUrl(sheetImage);
                        character.setModelingStatus(CharacterModelingStatus.COMPLETED);
                    }
                    character = characterRepository.save(character);
                }
                story.getCharacters().add(character);
                storyCharactersBySlug.put(normalizedSlug, character);
            }

            if (node.hasNonNull("visual_description")) {
                character.setVisualDescription(node.get("visual_description").asText());
            }
            if (node.hasNonNull("prompt_keywords")) {
                character.setPromptKeywords(node.get("prompt_keywords").asText());
            }
            if (node.hasNonNull("catchphrase")) {
                character.setCatchphrase(node.get("catchphrase").asText());
            }

            charactersToPersist.add(character);
            ensureCharacterReference(character, node.path("visual_description").asText(null));
            log.info("[CHARACTER_DEBUG] After ensureCharacterReference for slug '{}': Status={}, ImageUrl={}", character.getSlug(), character.getModelingStatus(), character.getImageUrl());
        });

        if (!charactersToPersist.isEmpty()) {
            characterRepository.saveAll(charactersToPersist);
        }

        storyRepository.save(story);
    }

    private String generateSlug(String slugCandidate, String name) {
        String base = (slugCandidate != null && !slugCandidate.isBlank()) ? slugCandidate : name;
        if (base == null) {
            base = "character";
        }
        String normalized = base.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.isBlank()) {
            normalized = UUID.randomUUID().toString();
        }
        return normalized;
    }

    private void ensureCharacterReference(Character character, String fallbackDescription) {
        if (character.getId() == null) {
            return;
        }
        if (character.getImageUrl() != null && !character.getImageUrl().isBlank()) {
            character.setModelingStatus(CharacterModelingStatus.COMPLETED);
            return;
        }
        String prompt = (fallbackDescription != null && !fallbackDescription.isBlank())
                ? fallbackDescription
                : character.getVisualDescription();

        Character updated = characterModelingService.requestModelingSync(character.getId(), prompt);
        if (updated != null && updated.getImageUrl() != null && !updated.getImageUrl().isBlank()) {
            character.setImageUrl(updated.getImageUrl());
            character.setModelingStatus(updated.getModelingStatus());
        }
    }

    private String resolveCharacterImageUrl(String rawImageUrl) {
        if (rawImageUrl == null || rawImageUrl.isBlank()) {
            return null;
        }

        String trimmed = rawImageUrl.trim();
        if (trimmed.startsWith("file:///") || trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }

        Path resolvedPath;
        if (trimmed.startsWith("/") || trimmed.matches("^[A-Za-z]:[\\\\/].*")) {
            resolvedPath = Paths.get(trimmed).toAbsolutePath().normalize();
        } else {
            String sanitized = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
            if (sanitized.startsWith("characters/")) {
                sanitized = sanitized.substring("characters/".length());
            }
            Path basePath = Paths.get(CHARACTER_IMAGE_DIR);
            resolvedPath = basePath.resolve(sanitized).toAbsolutePath().normalize();
        }

        String unixPath = resolvedPath.toString().replace("\\", "/");
        if (!unixPath.startsWith("/")) {
            unixPath = "/" + unixPath;
        }
        return "file://" + unixPath;
    }

    private StableStoryDto createFailsafeStory() {
        String title = "용감한 토끼의 모험";
        List<StableStoryPageDto> pages = List.of(
                new StableStoryPageDto("깊은 숲 속에 용감한 토끼 ‘토토’가 살고 있었어요. 토토는 친구들과 함께 숲을 탐험하는 것을 아주 좋아했답니다. 매일 새로운 길을 찾아 나서는 용감한 탐험가였죠.", null),
                new StableStoryPageDto("어느 날, 숲 속에서 가장 지혜로운 동물로 알려진 늙은 부엉이가 토토를 불렀어요. 부엉이는 토토에게 숲의 평화를 위협하는 심술궂은 여우에 대해 이야기해주었습니다.", null),
                new StableStoryPageDto("토토는 친구들을 지키기 위해 용기를 내기로 결심했어요. 토토는 함정을 만들어 심술궂은 여우를 골탕 먹이기로 했죠. 친구들과 함께 열심히 함정을 준비했습니다.", null),
                new StableStoryPageDto("결국 토토와 친구들은 힘을 합쳐 여우를 잡았고, 여우는 다시는 친구들을 괴롭히지 않겠다고 약속했어요. 숲에는 다시 평화가 찾아왔고 모두가 토토를 칭찬했답니다.", null)
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
        // 1. Aggregate all page texts into a single string
        String storyText = storyPageRepository.findByStoryIdOrderByPageNoAsc(story.getId()).stream()
                .sorted(Comparator.comparing(StoryPage::getPageNo))
                .map(StoryPage::getText)
                .collect(Collectors.joining("\n\n"));

        if (storyText.isEmpty()) {
            throw new IllegalStateException("Story has no content to generate audio from.");
        }

        story.getCharacters().size(); // initialize lazy collection

        List<GenerateAudioFromStoryRequestDto.CharacterProfileDto> characterDtos = story.getCharacters().stream()
                .sorted(Comparator.comparing(Character::getId))
                .map(character -> new GenerateAudioFromStoryRequestDto.CharacterProfileDto(
                        character.getId(),
                        character.getSlug(),
                        character.getName(),
                        character.getPersona(),
                        character.getCatchphrase(),
                        character.getPromptKeywords(),
                        character.getImageUrl()
                ))
                .collect(Collectors.toList());

        String language = Optional.ofNullable(story.getLanguage()).orElse("KO");

        // 2. Create new DTO for the new AI service endpoint
        GenerateAudioFromStoryRequestDto audioRequest = new GenerateAudioFromStoryRequestDto(
                storyText,
                characterDtos,
                language
        );

        // 3. Call the AI service to generate audio
        String audioFileName = webClient.post()
                .uri("/ai/generate-audio")
                .bodyValue(audioRequest)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        String audioUrl = "/api/audio/" + audioFileName;

        return audioUrl;
    }

    @Transactional
    public void generateAssetsForPage(Long storyId, Integer pageNo) {
        StoryPage storyPage = storyPageRepository.findByStoryIdAndPageNo(storyId, pageNo)
                .orElseThrow(() -> new IllegalArgumentException("StoryPage not found."));

        Story story = storyPage.getStory();
        story.getCharacters().size(); // Initialize lazy collection

        // Prepare character visuals for the AI service
        List<Map<String, String>> characterVisuals = story.getCharacters().stream()
                .map(character -> {
                    Map<String, String> visual = new HashMap<>();
                    visual.put("name", character.getName());
                    if (character.getSlug() != null) {
                        visual.put("slug", character.getSlug());
                    }
                    if (character.getVisualDescription() != null) {
                        visual.put("visual_description", character.getVisualDescription());
                    }
                    String resolvedImageUrl = resolveCharacterImageUrl(character.getImageUrl());
                    if (resolvedImageUrl != null) {
                        visual.put("image_url", resolvedImageUrl);
                    }
                    if (character.getModelingStatus() != null) {
                        visual.put("modeling_status", character.getModelingStatus().name());
                    }
                    return visual;
                })
                .collect(Collectors.toList());

        // Construct the request payload for the AI service
        ObjectNode requestPayload = objectMapper.createObjectNode();
        requestPayload.put("text", storyPage.getText());
        requestPayload.put("art_style", story.getCreativeConcept() != null ? story.getCreativeConcept() : ""); // Assuming creativeConcept can be used as art_style
        
        ArrayNode characterArray = requestPayload.putArray("character_visuals");
        characterVisuals.forEach(cv -> {
            ObjectNode characterNode = characterArray.addObject();
            cv.forEach(characterNode::put);
        });

        try {
            log.info("[CHARACTER_DEBUG] Payload for page {} asset generation: {}", pageNo, objectMapper.writeValueAsString(requestPayload));
        } catch (JsonProcessingException e) {
            log.warn("[CHARACTER_DEBUG] Failed to serialize character visuals for logging.");
        }

        try {
            JsonNode aiResponse = webClient.post()
                    .uri("/ai/generate-page-assets")
                    .bodyValue(requestPayload)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                            .filter(throwable -> throwable instanceof org.springframework.web.reactive.function.client.WebClientResponseException.ServiceUnavailable)
                            .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) ->
                                    new RuntimeException("AI asset generation service unavailable after retries.", retrySignal.failure())))
                    .block();

            if (aiResponse != null) {
                String imageUrl = aiResponse.get("imageUrl").asText();

                JsonNode characterProcessingNode = aiResponse.get("characterProcessingResults");
                if (characterProcessingNode != null && characterProcessingNode.isArray()) {
                    Map<String, Character> charactersBySlug = story.getCharacters().stream()
                            .filter(character -> character.getSlug() != null)
                            .collect(Collectors.toMap(
                                    character -> character.getSlug().toLowerCase(Locale.ROOT),
                                    Function.identity(),
                                    (existing, replacement) -> existing
                            ));
                    List<Character> updatedCharacters = new ArrayList<>();
                    characterProcessingNode.forEach(node -> {
                        String slug = node.path("slug").asText(null);
                        if (slug == null || slug.isBlank()) {
                            return;
                        }
                        Character character = charactersBySlug.get(slug.toLowerCase(Locale.ROOT));
                        if (character == null) {
                            return;
                        }
                        if (node.hasNonNull("imageUrl")) {
                            String updatedImageUrl = node.get("imageUrl").asText();
                            if ((character.getImageUrl() == null || character.getImageUrl().isBlank())
                                    && updatedImageUrl != null
                                    && updatedImageUrl.startsWith("file://")) {
                                character.setImageUrl(updatedImageUrl);
                            }
                        }
                        if (node.hasNonNull("modelingStatus")) {
                            String statusText = node.get("modelingStatus").asText();
                            try {
                                character.setModelingStatus(CharacterModelingStatus.valueOf(statusText));
                            } catch (IllegalArgumentException ignored) {
                                log.debug("Unknown modeling status '{}' from AI response for slug {}", statusText, slug);
                            }
                        }
                        updatedCharacters.add(character);
                    });
                    if (!updatedCharacters.isEmpty()) {
                        characterRepository.saveAll(updatedCharacters);
                    }
                }

                storyPage.setImageUrl(imageUrl);
                storyPageRepository.save(storyPage);
                log.info("Generated assets for StoryPage {}-{} and updated with imageUrl: {}", storyId, pageNo, imageUrl);
            } else {
                log.warn("Received null response from AI asset generation service for StoryPage {}-{}", storyId, pageNo);
            }

        } catch (Exception e) {
            log.error("Failed to generate assets for StoryPage {}-{} from AI service: {}", storyId, pageNo, e.getMessage());
            throw new RuntimeException("Failed to generate assets for story page.", e);
        }
    }
}
