package com.findme.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "profiles")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Profile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // For H2 and Postgres IDENTITY
    private Long id;

    @Column(name = "user_id", unique = true, nullable = false)
    private String userId;

    @Column(columnDefinition = "TEXT") // For H2, will be JSONB in Postgres
    private String traits; // Storing as String for flexibility

    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;
}
