package com.fairylearn.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "characters")
@Getter
@Setter
@NoArgsConstructor
public class Character {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String slug;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String persona;

    @Column(columnDefinition = "TEXT")
    private String catchphrase;

    @Column(name = "prompt_keywords", columnDefinition = "TEXT")
    private String promptKeywords;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "visual_description", columnDefinition = "TEXT")
    private String visualDescription;

    @Enumerated(EnumType.STRING)
    @Column(name = "modeling_status", nullable = false, length = 20)
    private CharacterModelingStatus modelingStatus = CharacterModelingStatus.PENDING;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @ManyToMany(mappedBy = "characters")
    private List<Story> stories = new ArrayList<>();

    @PrePersist
    public void onPersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (modelingStatus == null) {
            modelingStatus = CharacterModelingStatus.PENDING;
        }
    }
}
