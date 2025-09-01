package com.findme.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "results")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResultEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId; // Nullable for non-logged-in users

    @Column(name = "test_code", nullable = false)
    private String testCode;

    private double score;

    @Column(columnDefinition = "TEXT") // Store traits as JSON string
    private String traits;

    @Column(columnDefinition = "TEXT") // Store poem as text
    private String poem;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
