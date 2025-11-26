package com.jaramgle.backend.dto;

import com.jaramgle.backend.entity.BillingOrder;
import com.jaramgle.backend.entity.BillingOrderStatus;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AdminBillingOrderDto {
    Long id;
    Long userId;
    String productCode;
    String productName;
    int quantity;
    Integer pricePerUnit;
    Integer totalAmount;
    BillingOrderStatus status;
    LocalDateTime requestedAt;
    LocalDateTime paidAt;

    public static AdminBillingOrderDto fromEntity(BillingOrder entity) {
        return AdminBillingOrderDto.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .productCode(entity.getProductCode())
                .productName(entity.getProduct() != null ? entity.getProduct().getName() : null)
                .quantity(entity.getQuantity())
                .pricePerUnit(entity.getPricePerUnit())
                .totalAmount(entity.getTotalAmount())
                .status(entity.getStatus())
                .requestedAt(entity.getRequestedAt())
                .paidAt(entity.getPaidAt())
                .build();
    }
}
