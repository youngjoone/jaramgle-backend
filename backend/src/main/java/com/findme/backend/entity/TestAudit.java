package com.findme.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "test_audit")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private String action; // IMPORT, PUBLISH, ARCHIVE

    private String actor; // User who performed the action

    @Column(columnDefinition = "CLOB") // For H2, TEXT in Postgres
    private String snapshot; // JSON snapshot of the test_def at that time

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
