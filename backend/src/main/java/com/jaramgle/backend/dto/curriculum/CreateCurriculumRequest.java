package com.jaramgle.backend.dto.curriculum;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateCurriculumRequest {

    @NotBlank
    private String title;

    @NotBlank
    private String category;

    @JsonAlias({"subTopic", "sub_topic"})
    private String subTopic;

    @JsonAlias({"ageRange", "age_range"})
    private String ageRange;

    @NotBlank
    @JsonAlias({"baseLanguage", "base_language"})
    private String baseLanguage;

    @JsonAlias({"translationLanguage", "translation_language"})
    @Pattern(regexp = "^(KO|EN|JA|FR|ES|DE|ZH|NONE)?$", message = "지원하지 않는 번역 언어 코드입니다.")
    private String translationLanguage;

    @NotNull
    @Min(2)
    @Max(4)
    private Integer weeks;

    @JsonAlias({"generationMode", "generation_mode"})
    private String generationMode;

    @JsonAlias({"scheduleRule", "schedule_rule"})
    private String scheduleRule;

    @JsonAlias({"nextRunAt", "next_run_at"})
    private LocalDateTime nextRunAt;

    @JsonAlias({"defaultCharacterIds", "default_character_ids"})
    private List<Long> defaultCharacterIds;

    @JsonAlias({"defaultArtStyle", "default_art_style"})
    private String defaultArtStyle;

    @JsonAlias({"defaultVoice", "default_voice"})
    private String defaultVoice;

    @NotNull
    @Valid
    @Size(min = 1, max = 4)
    @JsonAlias({"weekGoals", "week_goals"})
    private List<WeekGoalRequest> weekGoals;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WeekGoalRequest {

        @NotNull
        @Min(1)
        @Max(4)
        @JsonAlias({"weekNo", "week_no"})
        private Integer weekNo;

        @NotBlank
        @JsonAlias({"primaryGoal", "primary_goal"})
        private String primaryGoal;

        @Size(max = 2)
        @JsonAlias({"subGoals", "sub_goals"})
        private List<@NotBlank String> subGoals;
    }
}
