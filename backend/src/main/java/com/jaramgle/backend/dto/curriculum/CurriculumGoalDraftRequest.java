package com.jaramgle.backend.dto.curriculum;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CurriculumGoalDraftRequest {

    @NotBlank
    private String category;

    @JsonAlias({"subTopic", "sub_topic"})
    private String subTopic;

    @JsonAlias({"ageRange", "age_range"})
    private String ageRange;

    @NotBlank
    @JsonAlias({"baseLanguage", "base_language"})
    private String baseLanguage;

    @NotNull
    @Min(2)
    @Max(4)
    private Integer weeks;

    private String title;
}
