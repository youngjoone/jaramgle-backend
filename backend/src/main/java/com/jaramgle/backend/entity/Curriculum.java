package com.jaramgle.backend.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "curriculums")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Curriculum {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private Integer weeks;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(name = "sub_topic")
    private String subTopic;

    @Column(name = "age_range")
    private String ageRange;

    @Column(name = "base_language", nullable = false, length = 10)
    private String baseLanguage;

    @Column(name = "translation_language", length = 10)
    private String translationLanguage;

    @Enumerated(EnumType.STRING)
    @Column(name = "generation_mode", nullable = false, length = 20)
    private CurriculumGenerationMode generationMode = CurriculumGenerationMode.ON_DEMAND;

    @Column(name = "schedule_rule", columnDefinition = "TEXT")
    private String scheduleRule;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CurriculumStatus status = CurriculumStatus.DRAFT;

    @Column(name = "default_character_ids_json", columnDefinition = "TEXT")
    private String defaultCharacterIdsJson;

    @Column(name = "default_art_style")
    private String defaultArtStyle;

    @Column(name = "default_voice")
    private String defaultVoice;

    @Column(name = "base_language_locked", nullable = false)
    private boolean baseLanguageLocked;

    @Column(nullable = false)
    private boolean deleted;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "curriculum", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("weekNo ASC")
    private List<CurriculumWeek> weekItems = new ArrayList<>();

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (generationMode == null) {
            generationMode = CurriculumGenerationMode.ON_DEMAND;
        }
        if (status == null) {
            status = CurriculumStatus.DRAFT;
        }
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
