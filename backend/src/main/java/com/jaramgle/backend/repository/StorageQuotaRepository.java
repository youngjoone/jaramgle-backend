package com.jaramgle.backend.repository;

import com.jaramgle.backend.entity.StorageQuota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StorageQuotaRepository extends JpaRepository<StorageQuota, String> {
    Optional<StorageQuota> findByUserId(String userId);
}