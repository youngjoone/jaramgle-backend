package com.jaramgle.backend.service;

import com.jaramgle.backend.entity.StorageQuota;
import com.jaramgle.backend.repository.StorageQuotaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class StorageQuotaService {

    private final StorageQuotaRepository storageQuotaRepository;

    private static final int DEFAULT_LIMIT = Integer.parseInt(
            System.getenv().getOrDefault("STORAGE_LIMIT_DEFAULT", "10"));

    @Transactional(readOnly = true)
    public StorageQuota getQuotaInfo(String userId) {
        return storageQuotaRepository.findByUserId(userId)
                .orElseGet(() -> createDefaultQuota(userId));
    }

    @Transactional
    public void ensureSlotAvailable(String userId) {
        StorageQuota quota = getQuotaInfo(userId);
        maybeUpgradeLimit(quota);
        if (quota.getUsedCnt() >= quota.getLimitCnt()) {
            throw new IllegalStateException("Storage limit exceeded for user: " + userId);
        }
    }

    @Transactional
    public void increaseUsedCount(String userId) {
        StorageQuota quota = getQuotaInfo(userId);
        quota.setUsedCnt(quota.getUsedCnt() + 1);
        quota.setUpdatedAt(LocalDateTime.now());
        storageQuotaRepository.save(quota);
    }

    @Transactional
    public void decreaseUsedCount(String userId) {
        StorageQuota quota = getQuotaInfo(userId);
        if (quota.getUsedCnt() > 0) {
            quota.setUsedCnt(quota.getUsedCnt() - 1);
            quota.setUpdatedAt(LocalDateTime.now());
            storageQuotaRepository.save(quota);
        }
    }

    private StorageQuota createDefaultQuota(String userId) {
        StorageQuota defaultQuota = new StorageQuota(userId, DEFAULT_LIMIT, 0, LocalDateTime.now());
        return storageQuotaRepository.save(defaultQuota);
    }

    /**
     * If the saved limit is lower than the current default, bump it up.
     * This helps when the default limit is increased for dev/local without manually updating rows.
     */
    private void maybeUpgradeLimit(StorageQuota quota) {
        if (quota.getLimitCnt() < DEFAULT_LIMIT) {
            quota.setLimitCnt(DEFAULT_LIMIT);
            quota.setUpdatedAt(LocalDateTime.now());
            storageQuotaRepository.save(quota);
        }
    }
}
