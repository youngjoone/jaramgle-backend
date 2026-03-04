package com.jaramgle.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "curriculum_episode_versions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"week_id", "version_no"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CurriculumEpisodeVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "week_id", nullable = false)
    private CurriculumWeek week;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "story_id", nullable = false)
    private Story story;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "week_status", nullable = false, length = 30)
    private String weekStatus;

    @Column(name = "story_text", columnDefinition = "TEXT")
    private String storyText;

    @Column(name = "asset_refs_json", columnDefinition = "TEXT")
    private String assetRefsJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
