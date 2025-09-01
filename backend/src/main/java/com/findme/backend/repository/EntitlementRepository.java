package com.findme.backend.repository;

import com.findme.backend.entity.Entitlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EntitlementRepository extends JpaRepository<Entitlement, Long> {
    Optional<Entitlement> findByUserIdAndItemCode(Long userId, String itemCode);
    List<Entitlement> findByUserId(Long userId);
}
