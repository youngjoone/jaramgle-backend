package com.fairylearn.backend.service;

import com.fairylearn.backend.dto.CharacterVisualDto; // Added
import com.fairylearn.backend.dto.GenerateParagraphAudioRequestDto;
import com.fairylearn.backend.dto.GenerateParagraphAudioResponseDto;
import com.fairylearn.backend.entity.Story;
import com.fairylearn.backend.entity.StoryPage;
import com.fairylearn.backend.entity.StorybookPage;
import com.fairylearn.backend.repository.StoryRepository;
import com.fairylearn.backend.repository.StorybookPageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@RequiredArgsConstructor
@Slf4j
public class StorybookService {

    private static final String CHARACTER_IMAGE_DIR = System.getenv().getOrDefault("CHARACTER_IMAGE_DIR", "/Users/kyj/testchardir");

    private final StoryRepository storyRepository;
    private final StorybookPageRepository storybookPageRepository;
    private final StoryService storyService;
    private final WebClient webClient;
    private final ObjectMapper objectMapper; // ADDED
    private final PlatformTransactionManager transactionManager;

    private final ExecutorService pageAssetExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()));

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
        StorybookPage firstStorybookPage = generateAndSaveStorybookPage(story, 1, firstOriginalPage.getText(), firstOriginalPage.getImagePrompt());
        log.info("createStorybook: First page generated and saved. ID: {}", firstStorybookPage.getId());

        if (originalPages.size() > 1) {
            List<StoryPage> remainingPages = originalPages.subList(1, originalPages.size());
            generateRemainingImages(story.getId(), remainingPages);
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

    @PreDestroy
    void shutdownExecutor() {
        pageAssetExecutor.shutdown();
    }

    @Async
    public void generateRemainingImages(Long storyId, List<StoryPage> remainingPages) {
        log.info("Starting async generation for {} remaining pages for storyId: {}", remainingPages.size(), storyId);
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < remainingPages.size(); i++) {
            final int pageNumber = i + 2;
            final StoryPage currentPage = remainingPages.get(i);

            log.info("Submitting page {} for parallel generation on storyId {} (thread={})",
                    pageNumber, storyId, Thread.currentThread().getName());

            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
                txTemplate.executeWithoutResult(status -> {
                    try {
                        log.info("Begin generation for storyId {} page {} (worker={})",
                                storyId, pageNumber, Thread.currentThread().getName());

                        Story transactionalStory = storyRepository.getReferenceById(storyId);
                        transactionalStory.getCharacters().size();
                        generateAndSaveStorybookPage(transactionalStory, pageNumber, currentPage.getText(), currentPage.getImagePrompt());
                        log.info("Completed generation for storyId {} page {} (worker={})",
                                storyId, pageNumber, Thread.currentThread().getName());
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
            }, pageAssetExecutor).exceptionally(ex -> {
                log.error("Async: Failed to generate image for storyId: {}, pageNumber: {}", storyId, pageNumber, ex);
                return null;
            });

            tasks.add(task);
        }

        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        log.info("Finished async generation for storyId: {}", storyId);
    }

    private StorybookPage generateAndSaveStorybookPage(Story story, int pageNumber, String pageText, String imagePrompt) {
        log.info("Generating image for storyId: {}, pageNumber: {}", story.getId(), pageNumber);

        String artStyle = "A minimalistic watercolor style with a soft pastel palette."; // Default
        List<CharacterVisualDto> characterVisuals = new ArrayList<>();

        String conceptJson = story.getCreativeConcept();
        if (conceptJson != null && !conceptJson.isEmpty()) {
            try {
                JsonNode conceptNode = objectMapper.readTree(conceptJson);
                artStyle = conceptNode.path("art_style").asText(artStyle);
                JsonNode sheetsNode = conceptNode.path("character_sheets");
                if (sheetsNode.isArray()) {
                    characterVisuals = StreamSupport.stream(sheetsNode.spliterator(), false)
                            .map(node -> new CharacterVisualDto(
                                    node.path("name").asText(),
                                    node.path("slug").asText(""),
                                    node.path("visual_description").asText(),
                                    node.path("image_url").asText(""), // Added imageUrl, defaulting to empty string
                                    node.path("modeling_status").asText("")
                            ))
                            .collect(Collectors.toList());
                }
            } catch (IOException e) {
                log.error("Failed to parse creative_concept JSON for storyId: {}. Using defaults.", story.getId(), e);
            }
        }

        String promptForImage = resolveImagePrompt(imagePrompt, pageText, artStyle, pageNumber, story.getId());

        List<CharacterVisualDto> characterVisualsForRequest = story.getCharacters().stream()
                .map(character -> createCharacterVisualDto(character, story.getId()))
                .collect(Collectors.toList());

        ObjectNode requestNode = objectMapper.createObjectNode();
        requestNode.put("text", promptForImage);
        requestNode.put("art_style", artStyle);

        ArrayNode characterArray = requestNode.putArray("character_visuals");
        Set<String> existingCharacterKeys = new HashSet<>();

        characterVisualsForRequest.forEach(visual -> {
            ObjectNode node = characterArray.addObject();
            node.put("name", visual.getName());
            if (visual.getSlug() != null && !visual.getSlug().isBlank()) {
                node.put("slug", visual.getSlug());
                existingCharacterKeys.add(visual.getSlug().toLowerCase());
            }
            if (visual.getVisualDescription() != null && !visual.getVisualDescription().isBlank()) {
                node.put("visual_description", visual.getVisualDescription());
            }
            if (visual.getImageUrl() != null && !visual.getImageUrl().isBlank()) {
                node.put("image_url", visual.getImageUrl());
            }
            if (visual.getModelingStatus() != null && !visual.getModelingStatus().isBlank()) {
                node.put("modeling_status", visual.getModelingStatus());
            }
            existingCharacterKeys.add(visual.getName().toLowerCase());
        });

        characterVisuals.stream()
                .filter(visual -> visual.getName() != null && !visual.getName().isBlank())
                .filter(visual -> {
                    String key = visual.getSlug() != null ? visual.getSlug().toLowerCase() : visual.getName().toLowerCase();
                    return !existingCharacterKeys.contains(key);
                })
                .forEach(visual -> {
                    ObjectNode node = characterArray.addObject();
                    node.put("name", visual.getName());
                    if (visual.getSlug() != null && !visual.getSlug().isBlank()) {
                        node.put("slug", visual.getSlug());
                        existingCharacterKeys.add(visual.getSlug().toLowerCase());
                    }
                    if (visual.getVisualDescription() != null && !visual.getVisualDescription().isBlank()) {
                        node.put("visual_description", visual.getVisualDescription());
                    }
                    if (visual.getImageUrl() != null && !visual.getImageUrl().isBlank()) {
                        node.put("image_url", visual.getImageUrl());
                    }
                    existingCharacterKeys.add(visual.getName().toLowerCase());
                });

        JsonNode assetResponse = webClient.post()
                .uri("/ai/generate-page-assets")
                .bodyValue(requestNode)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (assetResponse == null || assetResponse.get("imageUrl") == null) {
            throw new RuntimeException("Failed to get page asset response from AI service.");
        }

        String imagePath = assetResponse.get("imageUrl").asText();
        if (imagePath == null || imagePath.isBlank()) {
            throw new RuntimeException("Image URL missing in AI response.");
        }

        String webAccessibleUrl = String.format("http://localhost:8080%s", imagePath.startsWith("/") ? imagePath : ("/" + imagePath));

        StorybookPage storybookPage = new StorybookPage();
        storybookPage.setStory(story);
        storybookPage.setPageNumber(pageNumber);
        storybookPage.setText(pageText);
        storybookPage.setImageUrl(webAccessibleUrl);

        StorybookPage savedPage = storybookPageRepository.save(storybookPage);
        log.info("Saved storybook page {} for storyId: {}. Image URL: {}", pageNumber, story.getId(), webAccessibleUrl);
        return savedPage;
    }

    @Transactional
    public StorybookPage generatePageAudio(Long storyId,
                                           Long pageId,
                                           GenerateParagraphAudioRequestDto requestDto) {
        StorybookPage page = storybookPageRepository.findByIdAndStoryId(pageId, storyId)
                .orElseThrow(() -> new IllegalArgumentException("Storybook page not found for this story."));

        if (!requestDto.isForceRegenerate() && StringUtils.hasText(page.getAudioUrl())) {
            log.info("Audio already exists for storyId={}, pageId={}; returning cached audio.", storyId, pageId);
            return page;
        }

        String resolvedText = StringUtils.hasText(requestDto.getText()) ? requestDto.getText() : page.getText();
        if (!StringUtils.hasText(resolvedText)) {
            throw new IllegalArgumentException("Paragraph text is required to generate audio.");
        }

        GenerateParagraphAudioRequestDto outbound = new GenerateParagraphAudioRequestDto();
        outbound.setStoryId(String.valueOf(storyId));
        outbound.setPageId(String.valueOf(pageId));
        outbound.setParagraphId(requestDto.getParagraphId());
        outbound.setSpeakerSlug(requestDto.getSpeakerSlug());
        outbound.setEmotion(requestDto.getEmotion());
        outbound.setStyleHint(requestDto.getStyleHint());
        outbound.setLanguage(requestDto.getLanguage());
        outbound.setForceRegenerate(requestDto.isForceRegenerate());
        outbound.setText(resolvedText);

        log.info("Requesting paragraph audio generation for storyId={}, pageId={} (forceRegenerate={})",
                storyId, pageId, requestDto.isForceRegenerate());

        GenerateParagraphAudioResponseDto response = webClient.post()
                .uri("/ai/generate-page-audio")
                .bodyValue(outbound)
                .retrieve()
                .bodyToMono(GenerateParagraphAudioResponseDto.class)
                .block();

        if (response == null || !StringUtils.hasText(response.getUrl())) {
            throw new RuntimeException("Failed to receive audio URL from AI service.");
        }

        String audioUrl = toWebAccessibleUrl(response.getUrl());
        page.setAudioUrl(audioUrl);
        StorybookPage saved = storybookPageRepository.save(page);
        log.info("Saved audio for storyId={}, pageId={} at {}", storyId, pageId, audioUrl);
        return saved;
    }

    private String toWebAccessibleUrl(String providedUrl) {
        if (!StringUtils.hasText(providedUrl)) {
            return providedUrl;
        }
        if (providedUrl.startsWith("http://") || providedUrl.startsWith("https://")) {
            return providedUrl;
        }
        String normalized = providedUrl.startsWith("/") ? providedUrl : "/" + providedUrl;
        return "http://localhost:8080" + normalized;
    }

    private String resolveImagePrompt(String rawPrompt,
                                      String pageText,
                                      String artStyle,
                                      int pageNumber,
                                      Long storyId) {
        if (rawPrompt != null && !rawPrompt.isBlank()) {
            return rawPrompt;
        }

        String normalizedText = pageText != null ? pageText.replaceAll("\\s+", " ").trim() : "";
        if (normalizedText.length() > 180) {
            normalizedText = normalizedText.substring(0, 180).trim() + "...";
        }

        normalizedText = normalizedText
                .replaceAll("“[^”]*”", "")
                .replaceAll("\"[^\"]*\"", "")
                .replaceAll("'[^']*'", "")
                .trim()
                .replaceAll("\\s{2,}", " ");

        StringBuilder fallback = new StringBuilder();
        if (artStyle != null && !artStyle.isBlank()) {
            fallback.append(artStyle.trim());
        }

        if (!normalizedText.isBlank()) {
            if (fallback.length() > 0) {
                fallback.append(" | ");
            }
            fallback.append("Scene summary: ").append(normalizedText);
        }

        if (fallback.length() == 0) {
            fallback.append("Child-friendly illustration depicting the key moment of the story.");
        }

        if (fallback.indexOf("No speech bubbles or text overlays") < 0) {
            if (fallback.length() > 0) {
                fallback.append(" | ");
            }
            fallback.append("No speech bubbles or text overlays; convey dialogue through expressions, gestures, and atmosphere.");
        }

        if (fallback.indexOf("Use only the established story characters") < 0) {
            fallback.append(" | Use only the established story characters (max three in frame); do not introduce random extra humans except distant silhouettes.");
        }

        if (fallback.indexOf("Keep clothing and time period consistent") < 0) {
            fallback.append(" | Keep clothing and time period consistent with the story setting (default to modern casual unless explicitly historical).");
        }

        log.warn("No image prompt found for storyId {}, page {}. Using fallback prompt.", storyId, pageNumber);
        return fallback.toString();
    }

    private CharacterVisualDto createCharacterVisualDto(com.fairylearn.backend.entity.Character character, Long storyId) {
        String name = character.getName();
        String visualDescription = sanitizeVisualDescription(normalize(character.getVisualDescription()));
        String imageUrl = normalize(character.getImageUrl());

        if (visualDescription.isBlank()) {
            List<String> descriptorParts = new ArrayList<>();
            String persona = normalize(character.getPersona());
            if (!persona.isBlank()) {
                descriptorParts.add("Persona: " + persona);
            }

            String promptKeywords = normalize(character.getPromptKeywords());
            if (!promptKeywords.isBlank()) {
                descriptorParts.add("Visual cues: " + promptKeywords);
            }

            String catchphrase = normalize(character.getCatchphrase());
            if (!catchphrase.isBlank()) {
                descriptorParts.add("Catchphrase: \"" + catchphrase + "\" (use to hint personality)");
            }

            if (!descriptorParts.isEmpty()) {
                visualDescription = String.format(
                        "%s | %s",
                        "Child-friendly illustration of " + name,
                        String.join(" | ", descriptorParts)
                );
            } else {
                visualDescription = "Child-friendly illustration of " + name + " with warm colors and inviting expression.";
            }

            log.warn("Character {} in storyId {} lacks visual description. Using derived fallback.", name, storyId);
        }
        if (!visualDescription.toLowerCase().contains("speech") && !visualDescription.toLowerCase().contains("bubble")) {
            visualDescription = visualDescription + " | Depict " + name + " actively engaging with the scene, showing emotion through body language. No speech bubbles or text overlays.";
        }
        if (!visualDescription.toLowerCase().contains("modern")) {
            visualDescription = visualDescription + " | Modern casual outfit that matches the story's setting; avoid historical or anachronistic clothing unless explicitly defined.";
        }

        String resolvedImageUrl = resolveImageUrl(imageUrl);
        String slug = character.getSlug();
        String modelingStatus = character.getModelingStatus() != null ? character.getModelingStatus().name() : "";
        return new CharacterVisualDto(name, slug, visualDescription, resolvedImageUrl, modelingStatus);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String resolveImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }

        String trimmed = imageUrl.trim();
        if (trimmed.startsWith("file:///") || trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }

        String sanitized = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
        if (sanitized.startsWith("characters/")) {
            sanitized = sanitized.substring("characters/".length());
        }
        Path basePath = Paths.get(CHARACTER_IMAGE_DIR);
        Path resolvedPath = basePath.resolve(sanitized).toAbsolutePath().normalize();
        String unixPath = resolvedPath.toString().replace("\\", "/");
        if (!unixPath.startsWith("/")) {
            unixPath = "/" + unixPath;
        }
        return "file://" + unixPath;
    }

    private String sanitizeVisualDescription(String description) {
        if (description == null) {
            return "";
        }
        String cleaned = description
                .replaceAll("“[^”]*”", "")
                .replaceAll("\"[^\"]*\"", "")
                .replaceAll("'[^']*'", "")
                .trim()
                .replaceAll("\\s{2,}", " ");
        return cleaned;
    }
}
