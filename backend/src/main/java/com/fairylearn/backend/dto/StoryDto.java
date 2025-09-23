package com.fairylearn.backend.dto;

import com.fairylearn.backend.entity.Story;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
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
    private String shareSlug;
    private LocalDateTime sharedAt;
    private boolean manageable;
    private String fullAudioUrl;
    private Long authorId;
    private String authorNickname;

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
        dto.setFullAudioUrl(story.getFullAudioUrl());
        dto.setAuthorId(null);
        dto.setAuthorNickname(null);
        // Pages will be set separately for detail view
        return dto;
    }

    public static StoryDto fromEntityWithPages(Story story, List<StoryPageDto> pages) {
        StoryDto dto = fromEntity(story);
        dto.setPages(pages);
        return dto;
    }
}
