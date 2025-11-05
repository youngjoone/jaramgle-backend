package com.fairylearn.backend.repository;

import com.fairylearn.backend.entity.HeartProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HeartProductRepository extends JpaRepository<HeartProduct, String> {
    List<HeartProduct> findByActiveTrueOrderBySortOrderAsc();
}
