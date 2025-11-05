package com.fairylearn.backend.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class WalletSummaryDto {
    int balance;
    List<HeartTransactionDto> recentTransactions;
}
