package com.fairylearn.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "stories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Story {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String title;

    @Column(name = "age_range")
    private String ageRange;

    @Column(name = "topics_json", columnDefinition = "TEXT")
    private String topicsJson;

    private String language;

    @Column(name = "length_level")
    private String lengthLevel;

    private String status; // e.g., "DRAFT", "PUBLISHED", "ARCHIVED"

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(columnDefinition = "TEXT")
    private String quiz;

    @Column(name = "full_audio_url")
    private String fullAudioUrl;

    @Column(name = "creative_concept", columnDefinition = "TEXT")
    private String creativeConcept;

    @Column(name = "reading_plan", columnDefinition = "TEXT")
    private String readingPlan;

    @OneToMany(mappedBy = "story", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StorybookPage> storybookPages = new ArrayList<>();

    @ManyToMany
    @JoinTable(
            name = "story_characters",
            joinColumns = @JoinColumn(name = "story_id"),
            inverseJoinColumns = @JoinColumn(name = "character_id")
    )
    private Set<Character> characters = new LinkedHashSet<>();
}
