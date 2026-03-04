package com.jaramgle.backend.dto.curriculum;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CurriculumDetailDto {

    private Long id;
    private String title;
    private String category;
    private String subTopic;
    private String ageRange;
    private String baseLanguage;
    private Integer weeks;
    private String generationMode;
    private String scheduleRule;
    private LocalDateTime nextRunAt;
    private String status;
    private String defaultArtStyle;
    private String defaultVoice;
    private List<Long> defaultCharacterIds;
    private boolean baseLanguageLocked;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<CurriculumWeekDto> weekItems;
}
