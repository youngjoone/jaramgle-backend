package com.jaramgle.backend.dto;

import com.jaramgle.backend.entity.SharedStory;
import com.jaramgle.backend.entity.Story;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AdminStoryDto {
    Long id;
    String title;
    String userId;
    String language;
    boolean hidden;
    boolean deleted;
    String shareSlug;
    boolean shareHidden;
    LocalDateTime createdAt;

    public static AdminStoryDto fromEntity(Story story, SharedStory sharedStory) {
        return AdminStoryDto.builder()
                .id(story.getId())
                .title(story.getTitle())
                .userId(story.getUserId())
                .language(story.getLanguage())
                .hidden(story.isHidden())
                .deleted(story.isDeleted())
                .shareSlug(sharedStory != null ? sharedStory.getShareSlug() : null)
                .shareHidden(sharedStory != null && sharedStory.isHidden())
                .createdAt(story.getCreatedAt())
                .build();
    }
}
