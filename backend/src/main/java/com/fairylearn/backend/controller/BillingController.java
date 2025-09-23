package com.fairylearn.backend.controller;

import com.fairylearn.backend.dto.MockPaymentRequest;
import com.fairylearn.backend.dto.MockPaymentResponse;
import com.fairylearn.backend.service.BillingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;

    @PostMapping("/pay/mock")
    public ResponseEntity<MockPaymentResponse> processMockPayment(@RequestBody MockPaymentRequest request) {
        MockPaymentResponse response = billingService.processMockPayment(request);
        return ResponseEntity.ok(response);
    }

}
