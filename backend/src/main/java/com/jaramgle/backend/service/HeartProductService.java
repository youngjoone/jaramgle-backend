package com.jaramgle.backend.service;

import com.jaramgle.backend.entity.HeartProduct;
import com.jaramgle.backend.repository.HeartProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class HeartProductService {

    private final HeartProductRepository heartProductRepository;

    @Transactional(readOnly = true)
    public List<HeartProduct> getActiveProducts() {
        return heartProductRepository.findByActiveTrueOrderBySortOrderAsc();
    }

    @Transactional(readOnly = true)
    public HeartProduct getProduct(String code) {
        return heartProductRepository.findById(code)
                .orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다: " + code));
    }
}
