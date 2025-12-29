package com.jaramgle.backend.config;

import com.jaramgle.backend.entity.HeartProduct;
import com.jaramgle.backend.repository.HeartProductRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Ensure default heart products exist and stay in sync with the frontend codes/prices.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HeartProductBootstrap {

    private final HeartProductRepository heartProductRepository;

    // code -> [hearts, bonus, price, sortOrder]
    private static final Map<String, int[]> DEFAULT_PRODUCTS = Map.of(
            "HEART_5", new int[]{5, 0, 5000, 10},
            "HEART_10", new int[]{10, 0, 10000, 20},
            "HEART_20", new int[]{20, 0, 20000, 30},
            "HEART_30", new int[]{30, 0, 30000, 40}
    );

    @PostConstruct
    @Transactional
    public void ensureDefaultProducts() {
        DEFAULT_PRODUCTS.forEach((code, conf) -> {
            HeartProduct product = heartProductRepository.findById(code).orElseGet(HeartProduct::new);
            boolean isNew = product.getCode() == null;
            product.setCode(code);
            product.setName("하트 " + conf[0] + "개");
            product.setDescription("동화 생성용 하트 " + conf[0] + "개");
            product.setHearts(conf[0]);
            product.setBonusHearts(conf[1]);
            product.setPrice(conf[2]);
            product.setSortOrder(conf[3]);
            product.setActive(true);
            heartProductRepository.save(product);
            if (isNew) {
                log.info("[HEART_PRODUCT] created {}", code);
            }
        });
    }
}
