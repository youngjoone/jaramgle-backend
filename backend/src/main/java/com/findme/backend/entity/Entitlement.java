package com.findme.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "entitlements")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Entitlement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "item_code", nullable = false)
    private String itemCode;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt; // null for permanent

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
