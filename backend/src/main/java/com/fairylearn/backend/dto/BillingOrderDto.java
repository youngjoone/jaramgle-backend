package com.fairylearn.backend.dto;

import com.fairylearn.backend.entity.BillingOrder;
import com.fairylearn.backend.entity.BillingOrderStatus;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class BillingOrderDto {
    Long id;
    String productCode;
    String productName;
    int quantity;
    int pricePerUnit;
    int totalAmount;
    int heartsPerUnit;
    int bonusHeartsPerUnit;
    BillingOrderStatus status;
    LocalDateTime requestedAt;
    LocalDateTime paidAt;

    public static BillingOrderDto fromEntity(BillingOrder entity) {
        return BillingOrderDto.builder()
                .id(entity.getId())
                .productCode(entity.getProductCode())
                .productName(entity.getProduct() != null ? entity.getProduct().getName() : null)
                .quantity(entity.getQuantity())
                .pricePerUnit(entity.getPricePerUnit())
                .totalAmount(entity.getTotalAmount())
                .heartsPerUnit(entity.getHeartsPerUnit())
                .bonusHeartsPerUnit(entity.getBonusHeartsPerUnit())
                .status(entity.getStatus())
                .requestedAt(entity.getRequestedAt())
                .paidAt(entity.getPaidAt())
                .build();
    }
}
