package com.jaramgle.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "busan_story_sources")
@Getter
@Setter
@NoArgsConstructor
public class BusanStorySource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_source", nullable = false, length = 80)
    private String externalSource;

    @Column(name = "external_id", nullable = false, length = 120)
    private String externalId;

    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType = "ATTRACTION";

    @Column(nullable = false)
    private String title;

    @Column(name = "normalized_title")
    private String normalizedTitle;

    @Column(length = 100)
    private String district;

    @Column(columnDefinition = "TEXT")
    private String subtitle;

    @Column(columnDefinition = "TEXT")
    private String intro;

    @Column(columnDefinition = "TEXT")
    private String feature;

    @Column(columnDefinition = "TEXT")
    private String origin;

    @Column(name = "story_context", columnDefinition = "TEXT")
    private String storyContext;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(name = "thumbnail_url", columnDefinition = "TEXT")
    private String thumbnailUrl;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "photo_title")
    private String photoTitle;

    @Column(name = "photo_location")
    private String photoLocation;

    @Column(name = "photo_keywords", columnDefinition = "TEXT")
    private String photoKeywords;

    @Column(name = "data_sources", columnDefinition = "TEXT")
    private String dataSources;

    private Double lat;

    private Double lng;

    @Column(name = "quality_score", nullable = false)
    private int qualityScore;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void onPersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
