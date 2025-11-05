package com.fairylearn.backend.dto;

import com.fairylearn.backend.entity.HeartProduct;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class HeartProductResponse {
    String code;
    String name;
    String description;
    int hearts;
    int bonusHearts;
    int price;
    int sortOrder;

    public static HeartProductResponse fromEntity(HeartProduct product) {
        return HeartProductResponse.builder()
                .code(product.getCode())
                .name(product.getName())
                .description(product.getDescription())
                .hearts(product.getHearts())
                .bonusHearts(product.getBonusHearts())
                .price(product.getPrice())
                .sortOrder(product.getSortOrder())
                .build();
    }
}
