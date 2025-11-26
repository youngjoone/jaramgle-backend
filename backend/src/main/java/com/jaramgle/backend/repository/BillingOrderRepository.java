package com.jaramgle.backend.repository;

import com.jaramgle.backend.entity.BillingOrder;
import com.jaramgle.backend.entity.BillingOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @EntityGraph(attributePaths = "product")
    @Query("""
        SELECT o FROM BillingOrder o
        WHERE (:status IS NULL OR o.status = :status)
          AND (:userId IS NULL OR o.userId = :userId)
        ORDER BY o.requestedAt DESC
        """)
    Page<BillingOrder> searchForAdmin(@Param("status") BillingOrderStatus status,
                                      @Param("userId") Long userId,
                                      Pageable pageable);
}
