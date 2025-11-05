package com.fairylearn.backend.repository;

import com.fairylearn.backend.entity.HeartTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HeartTransactionRepository extends JpaRepository<HeartTransaction, Long> {

    List<HeartTransaction> findTop10ByUserIdOrderByCreatedAtDesc(Long userId);

    Page<HeartTransaction> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
