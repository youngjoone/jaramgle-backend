package com.jaramgle.backend.dto.curriculum;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CurriculumActionResponse {
    private CurriculumWeekDto week;
    private CurriculumJobDto job;
}
