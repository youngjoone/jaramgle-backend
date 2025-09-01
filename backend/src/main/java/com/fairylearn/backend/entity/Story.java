package com.fairylearn.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

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
}