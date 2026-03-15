package com.jaramgle.backend.dto.curriculum;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateWeekGoalRequest {

    @NotBlank
    @JsonAlias({"primaryGoal", "primary_goal"})
    private String primaryGoal;

    @Size(max = 2)
    @JsonAlias({"subGoals", "sub_goals"})
    private List<@NotBlank String> subGoals;
}
