package com.fairylearn.backend.dto;

import com.fairylearn.backend.entity.HeartTransaction;
import com.fairylearn.backend.entity.HeartTransactionType;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class HeartTransactionDto {
    Long id;
    HeartTransactionType type;
    int amount;
    int balanceAfter;
    String description;
    LocalDateTime createdAt;

    public static HeartTransactionDto fromEntity(HeartTransaction entity) {
        return HeartTransactionDto.builder()
                .id(entity.getId())
                .type(entity.getType())
                .amount(entity.getAmount())
                .balanceAfter(entity.getBalanceAfter())
                .description(entity.getDescription())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
