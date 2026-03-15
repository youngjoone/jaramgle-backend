package com.jaramgle.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminCleanupCandidateDto {

    private Long storyId;
    private String title;
    private String userId;
    private String language;
    private LocalDateTime createdAt;
}
