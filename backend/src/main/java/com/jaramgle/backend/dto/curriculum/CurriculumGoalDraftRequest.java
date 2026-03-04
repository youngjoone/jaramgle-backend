package com.jaramgle.backend.dto.curriculum;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CurriculumGoalDraftRequest {

    @NotBlank
    private String category;

    private String subTopic;

    private String ageRange;

    @NotBlank
    private String baseLanguage;

    @NotNull
    @Min(2)
    @Max(4)
    private Integer weeks;

    private String title;
}
