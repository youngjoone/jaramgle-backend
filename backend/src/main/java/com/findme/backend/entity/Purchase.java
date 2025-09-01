package com.findme.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "purchases")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Purchase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId; // Nullable for non-logged-in users

    @Column(name = "item_code", nullable = false)
    private String itemCode;

    private int amount;

    @Column(nullable = false)
    private String status; // PAID, CANCELED

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
