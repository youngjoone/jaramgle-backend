package com.jaramgle.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminCurriculumOrphanPreviewDto {

    private Integer olderThanMinutes;
    private Integer limit;
    private Long totalCandidates;
    private List<AdminCleanupCandidateDto> candidates;
}
