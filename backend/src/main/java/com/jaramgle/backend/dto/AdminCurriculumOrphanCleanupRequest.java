package com.jaramgle.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminCurriculumOrphanCleanupRequest {

    @Min(30)
    @Max(10080)
    private Integer olderThanMinutes = 60;

    @Min(1)
    @Max(500)
    private Integer limit = 100;
}
