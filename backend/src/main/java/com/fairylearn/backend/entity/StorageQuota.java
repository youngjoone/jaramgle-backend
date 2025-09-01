package com.fairylearn.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "storage_quotas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class StorageQuota {
    @Id
    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "limit_cnt", nullable = false)
    private Integer limitCnt;

    @Column(name = "used_cnt", nullable = false)
    private Integer usedCnt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}