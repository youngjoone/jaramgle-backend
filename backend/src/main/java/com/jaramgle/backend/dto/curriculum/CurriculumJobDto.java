package com.jaramgle.backend.dto.curriculum;

import com.jaramgle.backend.entity.CurriculumJob;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CurriculumJobDto {
    private Long id;
    private String jobType;
    private String status;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime queuedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public static CurriculumJobDto fromEntity(CurriculumJob job) {
        if (job == null) {
            return null;
        }
        return new CurriculumJobDto(
                job.getId(),
                job.getJobType() == null ? null : job.getJobType().name(),
                job.getStatus() == null ? null : job.getStatus().name(),
                job.getErrorCode(),
                job.getErrorMessage(),
                job.getQueuedAt(),
                job.getStartedAt(),
                job.getFinishedAt()
        );
    }
}
