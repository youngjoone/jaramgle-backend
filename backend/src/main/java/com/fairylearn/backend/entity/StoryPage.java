package com.fairylearn.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "story_pages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class StoryPage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "story_id", nullable = false)
    private Story story;

    @Column(name = "page_no", nullable = false)
    private Integer pageNo;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String text;

    @Column(name = "image_prompt", columnDefinition = "TEXT")
    private String imagePrompt;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "audio_url")
    private String audioUrl;
}
