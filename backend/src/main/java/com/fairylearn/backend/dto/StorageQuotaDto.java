package com.fairylearn.backend.dto;

import com.fairylearn.backend.entity.StorageQuota;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StorageQuotaDto {
    private Integer limit;
    private Integer used;

    public static StorageQuotaDto fromEntity(StorageQuota quota) {
        return new StorageQuotaDto(quota.getLimitCnt(), quota.getUsedCnt());
    }
}