package com.fairylearn.backend.controller;

import com.fairylearn.backend.auth.AuthPrincipal;
import com.fairylearn.backend.dto.HeartTransactionDto;
import com.fairylearn.backend.dto.PageResponse;
import com.fairylearn.backend.dto.WalletSummaryDto;
import com.fairylearn.backend.entity.HeartTransaction;
import com.fairylearn.backend.service.HeartWalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final HeartWalletService heartWalletService;

    @GetMapping("/me")
    public ResponseEntity<WalletSummaryDto> getMyWallet(@AuthenticationPrincipal AuthPrincipal principal) {
        int balance = heartWalletService.getBalance(principal.id());
        List<HeartTransactionDto> transactions = heartWalletService.getRecentTransactions(principal.id(), 10)
                .stream()
                .map(HeartTransactionDto::fromEntity)
                .toList();
        return ResponseEntity.ok(WalletSummaryDto.builder()
                .balance(balance)
                .recentTransactions(transactions)
                .build());
    }

    @GetMapping("/me/transactions")
    public ResponseEntity<PageResponse<HeartTransactionDto>> getMyTransactions(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Page<HeartTransaction> transactions = heartWalletService.getTransactions(
                principal.id(),
                PageRequest.of(Math.max(page, 0), Math.min(size, 50))
        );
        Page<HeartTransactionDto> dtoPage = transactions.map(HeartTransactionDto::fromEntity);
        return ResponseEntity.ok(PageResponse.of(dtoPage));
    }
}
