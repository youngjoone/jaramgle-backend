package com.findme.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "tests")
@Data
@NoArgsConstructor // Required for JPA
@AllArgsConstructor
public class Test {
    @Id
    private String code;

    private String title;

    private int version;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "test", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Question> questions;

    // Custom constructor for initial data without questions list
    public Test(String code, String title, int version, LocalDateTime createdAt) {
        this.code = code;
        this.title = title;
        this.version = version;
        this.createdAt = createdAt;
    }
}