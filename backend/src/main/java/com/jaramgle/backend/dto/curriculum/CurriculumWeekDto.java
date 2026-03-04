package com.jaramgle.backend.dto.curriculum;

import com.jaramgle.backend.entity.CurriculumWeek;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CurriculumWeekDto {

    private Long id;
    private Integer weekNo;
    private String primaryGoal;
    private List<String> subGoals;
    private String status;
    private Long storyId;
    private Integer currentVersionNo;
    private boolean continuityStale;
    private boolean autoRetryUsed;
    private boolean manualRetryUsed;
    private String skipReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private CurriculumJobDto latestJob;
}
