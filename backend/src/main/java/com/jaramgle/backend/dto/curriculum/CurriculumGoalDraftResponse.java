package com.jaramgle.backend.dto.curriculum;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CurriculumGoalDraftResponse {
    private List<WeekGoal> goals;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WeekGoal {
        private Integer weekNo;
        private String primaryGoal;
        private List<String> subGoals;
    }
}
