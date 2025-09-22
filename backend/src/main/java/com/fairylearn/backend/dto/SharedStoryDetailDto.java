package com.fairylearn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class SharedStoryDetailDto {
    private String shareSlug;
    private String title;
    private LocalDateTime sharedAt;
    private boolean manageable;
    private StoryDto story;
}
