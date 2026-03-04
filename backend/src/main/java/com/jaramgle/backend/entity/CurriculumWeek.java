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
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "curriculum_weeks",
        uniqueConstraints = @UniqueConstraint(columnNames = {"curriculum_id", "week_no"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CurriculumWeek {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "curriculum_id", nullable = false)
    private Curriculum curriculum;

    @Column(name = "week_no", nullable = false)
    private Integer weekNo;

    @Column(name = "primary_goal", nullable = false, columnDefinition = "TEXT")
    private String primaryGoal;

    @Column(name = "sub_goals_json", columnDefinition = "TEXT")
    private String subGoalsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CurriculumWeekStatus status = CurriculumWeekStatus.NOT_STARTED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "story_id")
    private Story story;

    @Column(name = "current_version_no", nullable = false)
    private Integer currentVersionNo = 0;

    @Column(name = "continuity_stale", nullable = false)
    private boolean continuityStale;

    @Column(name = "auto_retry_used", nullable = false)
    private boolean autoRetryUsed;

    @Column(name = "manual_retry_used", nullable = false)
    private boolean manualRetryUsed;

    @Column(name = "skip_reason", columnDefinition = "TEXT")
    private String skipReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = CurriculumWeekStatus.NOT_STARTED;
        }
        if (currentVersionNo == null) {
            currentVersionNo = 0;
        }
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
