package com.findme.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "test_defs")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestDef {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String status; // DRAFT, PUBLISHED, ARCHIVED

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "CLOB")
    private JsonNode questions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "CLOB")
    private JsonNode scoring;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
