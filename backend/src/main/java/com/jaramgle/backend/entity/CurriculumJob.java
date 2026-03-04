package com.jaramgle.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "curriculum_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CurriculumJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "curriculum_id", nullable = false)
    private Curriculum curriculum;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "week_id", nullable = false)
    private CurriculumWeek week;

    @Column(name = "week_no", nullable = false)
    private Integer weekNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 20)
    private CurriculumJobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CurriculumJobStatus status = CurriculumJobStatus.PENDING;

    @Column(name = "request_snapshot_json", columnDefinition = "TEXT")
    private String requestSnapshotJson;

    @Column(name = "charge_required", nullable = false)
    private boolean chargeRequired = true;

    @Column(nullable = false)
    private boolean charged;

    @Column(nullable = false)
    private boolean refunded;

    @Column(name = "heart_amount", nullable = false)
    private Integer heartAmount = 1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "retry_of_job_id")
    private CurriculumJob retryOfJob;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    @Column(name = "queued_at", nullable = false)
    private LocalDateTime queuedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "timeout_at")
    private LocalDateTime timeoutAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (queuedAt == null) {
            queuedAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = CurriculumJobStatus.PENDING;
        }
        if (heartAmount == null || heartAmount <= 0) {
            heartAmount = 1;
        }
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
