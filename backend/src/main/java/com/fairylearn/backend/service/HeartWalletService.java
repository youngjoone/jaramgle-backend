package com.fairylearn.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fairylearn.backend.entity.BillingOrder;
import com.fairylearn.backend.entity.HeartTransaction;
import com.fairylearn.backend.entity.HeartTransactionType;
import com.fairylearn.backend.entity.HeartWallet;
import com.fairylearn.backend.exception.InsufficientHeartsException;
import com.fairylearn.backend.repository.HeartTransactionRepository;
import com.fairylearn.backend.repository.HeartWalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class HeartWalletService {

    private final HeartWalletRepository walletRepository;
    private final HeartTransactionRepository transactionRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public int getBalance(Long userId) {
        return walletRepository.findByUserId(userId)
                .map(HeartWallet::getBalance)
                .orElse(0);
    }

    @Transactional
    public HeartWallet ensureWallet(Long userId) {
        return walletRepository.findByUserIdForUpdate(userId)
                .orElseGet(() -> {
                    HeartWallet created = new HeartWallet(userId, 0);
                    created.setUpdatedAt(LocalDateTime.now());
                    try {
                        return walletRepository.save(created);
                    } catch (DataIntegrityViolationException ex) {
                        return walletRepository.findByUserIdForUpdate(userId)
                                .orElseThrow(() -> ex);
                    }
                });
    }

    @Transactional
    public HeartTransaction chargeHearts(Long userId, int hearts, HeartTransactionType type, String description, BillingOrder order, Map<String, Object> metadata) {
        if (hearts <= 0) {
            throw new IllegalArgumentException("hearts must be positive when charging");
        }
        HeartWallet wallet = ensureWallet(userId);
        wallet.setBalance(wallet.getBalance() + hearts);
        wallet.setUpdatedAt(LocalDateTime.now());
        walletRepository.save(wallet);

        HeartTransaction transaction = new HeartTransaction(
                userId,
                order,
                hearts,
                wallet.getBalance(),
                type,
                description,
                toMetadata(metadata)
        );
        return transactionRepository.save(transaction);
    }

    @Transactional
    public HeartTransaction spendHearts(Long userId, int hearts, String description, Map<String, Object> metadata) {
        if (hearts <= 0) {
            throw new IllegalArgumentException("hearts must be positive when spending");
        }
        HeartWallet wallet = ensureWallet(userId);
        if (wallet.getBalance() < hearts) {
            throw new InsufficientHeartsException("INSUFFICIENT_HEARTS", "하트가 부족합니다. 충전이 필요해요.");
        }

        wallet.setBalance(wallet.getBalance() - hearts);
        wallet.setUpdatedAt(LocalDateTime.now());
        walletRepository.save(wallet);

        HeartTransaction transaction = new HeartTransaction(
                userId,
                null,
                -hearts,
                wallet.getBalance(),
                HeartTransactionType.SPEND,
                description,
                toMetadata(metadata)
        );
        return transactionRepository.save(transaction);
    }

    @Transactional(readOnly = true)
    public List<HeartTransaction> getRecentTransactions(Long userId, int limit) {
        return transactionRepository.findTop10ByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .limit(limit)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<HeartTransaction> getTransactions(Long userId, Pageable pageable) {
        return transactionRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public void assertSufficientBalance(Long userId, int hearts) {
        if (hearts <= 0) {
            throw new IllegalArgumentException("hearts must be positive");
        }
        int balance = getBalance(userId);
        if (balance < hearts) {
            throw new InsufficientHeartsException("INSUFFICIENT_HEARTS", "하트가 부족합니다. 충전이 필요해요.");
        }
    }

    private String toMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize heart transaction metadata: {}", metadata, e);
            return null;
        }
    }
}
