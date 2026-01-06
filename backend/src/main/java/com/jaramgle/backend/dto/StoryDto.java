package com.jaramgle.backend.dto;

import com.jaramgle.backend.entity.Story;
import com.jaramgle.backend.util.AssetUrlResolver;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.time.LocalDateTime;
import java.util.ArrayList; // Added
import java.util.List;
import java.util.stream.Collectors;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoryDto {
    private Long id;
    private String title;
    private String ageRange;
    private List<String> topics; // Converted from topicsJson
    private String language;
    private String lengthLevel;
    private String status;
    private LocalDateTime createdAt;
    private List<StoryPageDto> pages; // For detail view
    private List<CharacterDto> characters;
    private String shareSlug;
    private LocalDateTime sharedAt;
    private boolean manageable;
    private String creativeConcept; // ADDED
    private String coverImageUrl;
    private Long authorId;
    private String authorNickname;
    private String voicePreset;
    private String translationLanguage;
    private TranslationDto translation;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TranslationDto {
        private String title;
        private List<TranslatedPageDto> pages;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TranslatedPageDto {
        private Integer page;
        private String text;
    }

    public static StoryDto fromEntity(Story story) {
        StoryDto dto = new StoryDto();
        dto.setId(story.getId());
        dto.setTitle(story.getTitle());
        dto.setAgeRange(story.getAgeRange());
        // Assuming topicsJson is a comma-separated string or similar simple format
        // For proper JSON parsing, a JSON library would be needed
        dto.setTopics(story.getTopicsJson() != null ? List.of(story.getTopicsJson().split(",")) : List.of());
        dto.setLanguage(story.getLanguage());
        dto.setLengthLevel(story.getLengthLevel());
        dto.setStatus(story.getStatus());
        dto.setCreatedAt(story.getCreatedAt());
        dto.setManageable(false);
        dto.setCreativeConcept(story.getCreativeConcept()); // ADDED
        dto.setCoverImageUrl(AssetUrlResolver.toPublicUrl(story.getCoverImageUrl()));
        dto.setAuthorId(null);
        dto.setAuthorNickname(null);
        dto.setPages(List.of());
        dto.setVoicePreset(story.getVoicePreset());
        dto.setTranslationLanguage(story.getTranslationLanguage());
        dto.setTranslation(parseTranslation(story.getTranslations()));
        dto.setCharacters(story.getCharacters() != null
                ? story.getCharacters().stream()
                    .map(CharacterDtoMapper::fromEntity)
                    .collect(Collectors.toList())
                : new ArrayList<>());
        // Pages will be set separately for detail view
        return dto;
    }

    public static StoryDto fromEntityWithPages(Story story, List<StoryPageDto> pages) {
        StoryDto dto = fromEntity(story);
        dto.setPages(pages);
        return dto;
    }

    private static TranslationDto parseTranslation(String translationsJson) {
        if (translationsJson == null || translationsJson.isBlank()) {
            return null;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            var root = mapper.readTree(translationsJson);
            if (root == null || root.isMissingNode() || root.isNull()) {
                return null;
            }
            String tTitle = root.path("title").asText(null);
            List<TranslatedPageDto> pages = new ArrayList<>();
            if (root.has("pages") && root.get("pages").isArray()) {
                for (var node : root.get("pages")) {
                    int pageNo = node.path("page").asInt(
                            node.path("pageNo").asInt(node.path("page_no").asInt(-1))
                    );
                    String text = node.path("text").asText(null);
                    if (pageNo > 0 && text != null && !text.isBlank()) {
                        pages.add(new TranslatedPageDto(pageNo, text));
                    }
                }
            }
            if (pages.isEmpty()) {
                return null;
            }
            return new TranslationDto(tTitle, pages);
        } catch (Exception e) {
            return null;
        }
    }
}
