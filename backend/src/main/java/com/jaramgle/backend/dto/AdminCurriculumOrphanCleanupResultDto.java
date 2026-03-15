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
public class AdminCurriculumOrphanCleanupResultDto {

    private Integer olderThanMinutes;
    private Integer limit;
    private Integer attemptedCount;
    private Integer deletedCount;
    private Integer failedCount;
    private List<Long> deletedStoryIds;
    private List<AdminCleanupFailureDto> failures;
}
