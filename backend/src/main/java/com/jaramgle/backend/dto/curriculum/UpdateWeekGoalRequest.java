package com.jaramgle.backend.dto.curriculum;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class UpdateWeekGoalRequest {

    @NotBlank
    private String primaryGoal;

    @Size(max = 2)
    private List<@NotBlank String> subGoals;
}
