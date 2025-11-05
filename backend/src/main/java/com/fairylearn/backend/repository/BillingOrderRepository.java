package com.fairylearn.backend.repository;

import com.fairylearn.backend.entity.BillingOrder;
import com.fairylearn.backend.entity.BillingOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BillingOrderRepository extends JpaRepository<BillingOrder, Long> {

    @EntityGraph(attributePaths = "product")
    Optional<BillingOrder> findByIdAndUserId(Long id, Long userId);

    @EntityGraph(attributePaths = "product")
    List<BillingOrder> findTop10ByUserIdOrderByRequestedAtDesc(Long userId);

    @EntityGraph(attributePaths = "product")
    Page<BillingOrder> findByUserIdOrderByRequestedAtDesc(Long userId, Pageable pageable);

    long countByUserIdAndStatus(Long userId, BillingOrderStatus status);
}
