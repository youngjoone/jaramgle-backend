package com.jaramgle.backend.dto.curriculum;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CurriculumSummaryDto {

    private Long id;
    private String title;
    private String category;
    private String subTopic;
    private String ageRange;
    private String baseLanguage;
    private Integer weeks;
    private Integer completedWeeks;
    private String status;
    private Integer nextWeekToGenerate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
