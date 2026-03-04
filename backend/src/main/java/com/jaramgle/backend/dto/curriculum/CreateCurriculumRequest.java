package com.jaramgle.backend.dto.curriculum;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CreateCurriculumRequest {

    @NotBlank
    private String title;

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

    private String generationMode;

    private String scheduleRule;

    private LocalDateTime nextRunAt;

    private List<Long> defaultCharacterIds;

    private String defaultArtStyle;

    private String defaultVoice;

    @NotNull
    @Valid
    @Size(min = 1, max = 4)
    private List<WeekGoalRequest> weekGoals;

    @Data
    public static class WeekGoalRequest {

        @NotNull
        @Min(1)
        @Max(4)
        private Integer weekNo;

        @NotBlank
        private String primaryGoal;

        @Size(max = 2)
        private List<@NotBlank String> subGoals;
    }
}
