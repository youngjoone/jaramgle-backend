package com.fairylearn.backend.repository;

import com.fairylearn.backend.entity.HeartWallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface HeartWalletRepository extends JpaRepository<HeartWallet, Long> {

    Optional<HeartWallet> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from HeartWallet w where w.userId = :userId")
    Optional<HeartWallet> findByUserIdForUpdate(@Param("userId") Long userId);
}
