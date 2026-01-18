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
    private List<QuizItemDto> quiz;
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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuizItemDto {
        private String question;
        private List<String> options;
        private Integer answer;
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
        dto.setQuiz(parseQuiz(story.getQuiz()));
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
                            node.path("pageNo").asInt(node.path("page_no").asInt(-1)));
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

    private static List<QuizItemDto> parseQuiz(String quizJson) {
        if (quizJson == null || quizJson.isBlank()) {
            return List.of();
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            var root = mapper.readTree(quizJson);
            if (root == null || root.isMissingNode()) {
                return List.of();
            }
            List<QuizItemDto> result = new ArrayList<>();
            if (root.isArray()) {
                for (var node : root) {
                    String q = node.path("question").asText(
                            node.path("q").asText(
                                    node.path("quiz_text").asText(null)));
                    List<String> options = new ArrayList<>();
                    if (node.has("options") && node.get("options").isArray()) {
                        for (var opt : node.get("options")) {
                            String val = opt.asText(null);
                            if (val != null)
                                options.add(val);
                        }
                    }
                    Integer a = null;
                    if (node.has("answer") && node.get("answer").isInt()) {
                        a = node.get("answer").asInt();
                    } else if (node.has("a") && node.get("a").isInt()) {
                        a = node.get("a").asInt();
                    } else if (node.has("answerIndex") && node.get("answerIndex").isInt()) {
                        a = node.get("answerIndex").asInt();
                    } else if (node.has("correct_choice") && node.get("correct_choice").isInt()) {
                        a = node.get("correct_choice").asInt();
                    }
                    if (q != null && !q.isBlank() && options.size() >= 2 && a != null) {
                        result.add(new QuizItemDto(q, options, a));
                    }
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }
}
