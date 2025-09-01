package com.findme.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MockPaymentRequest {
    private String itemCode;
    private int amount;
}
