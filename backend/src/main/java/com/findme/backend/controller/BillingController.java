package com.findme.backend.controller;

import com.findme.backend.dto.EntitlementDto;
import com.findme.backend.dto.MockPaymentRequest;
import com.findme.backend.dto.MockPaymentResponse;
import com.findme.backend.service.BillingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    @GetMapping("/entitlements/me")
    public ResponseEntity<List<EntitlementDto>> getUserEntitlements() {
        List<EntitlementDto> entitlements = billingService.getUserEntitlements();
        return ResponseEntity.ok(entitlements);
    }
}
