package com.fairylearn.backend.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "users", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"provider", "provider_id"})
})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name; // nickname for email users, profile name for social users

    @Column(unique = true, nullable = false)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash; // For email-based login

    private String provider; // e.g., "local", "naver", "google"

    @Column(name = "provider_id")
    private String providerId; // ID from the provider

    private String role;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Builder
    public User(String name, String email, String passwordHash, String provider, String providerId, String role, LocalDateTime createdAt) {
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.provider = provider;
        this.providerId = providerId;
        this.role = role;
        this.createdAt = createdAt;
    }

    public User update(String name, String email) {
        this.name = name;
        this.email = email;
        return this;
    }

    public String getRoleKey() {
        return this.role;
    }

    // Setter methods for fields used in AuthService
    public void setEmail(String email) { this.email = email; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public void setName(String name) { this.name = name; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setProvider(String provider) { this.provider = provider; }
    public void setProviderId(String providerId) { this.providerId = providerId; } // New setter
    public void setRole(String role) { this.role = role; } // New setter
}
