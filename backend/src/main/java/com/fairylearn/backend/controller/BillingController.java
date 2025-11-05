package com.fairylearn.backend.controller;

import com.fairylearn.backend.auth.AuthPrincipal;
import com.fairylearn.backend.dto.BillingOrderDto;
import com.fairylearn.backend.dto.ConfirmOrderRequest;
import com.fairylearn.backend.dto.CreateOrderRequest;
import com.fairylearn.backend.dto.HeartProductResponse;
import com.fairylearn.backend.dto.PageResponse;
import com.fairylearn.backend.entity.BillingOrder;
import com.fairylearn.backend.service.BillingOrderService;
import com.fairylearn.backend.service.HeartProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingController {

    private final HeartProductService heartProductService;
    private final BillingOrderService billingOrderService;

    @GetMapping("/products")
    public ResponseEntity<List<HeartProductResponse>> listProducts() {
        List<HeartProductResponse> products = heartProductService.getActiveProducts().stream()
                .map(HeartProductResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(products);
    }

    @PostMapping("/orders")
    public ResponseEntity<BillingOrderDto> createOrder(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        BillingOrder order = billingOrderService.createOrder(principal.id(), request.getProductCode(), request.getQuantity());
        return ResponseEntity.ok(BillingOrderDto.fromEntity(order));
    }

    @PostMapping("/orders/{orderId}/confirm")
    public ResponseEntity<BillingOrderDto> confirmOrder(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long orderId,
            @RequestBody(required = false) ConfirmOrderRequest request
    ) {
        String paymentKey = request != null ? request.getPaymentKey() : null;
        String pgProvider = request != null ? request.getPgProvider() : null;
        BillingOrder order = billingOrderService.confirmPayment(principal.id(), orderId, paymentKey, pgProvider);
        return ResponseEntity.ok(BillingOrderDto.fromEntity(order));
    }

    @GetMapping("/orders")
    public ResponseEntity<PageResponse<BillingOrderDto>> listOrders(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Page<BillingOrder> orders = billingOrderService.getOrders(principal.id(), PageRequest.of(Math.max(page, 0), Math.min(size, 50)));
        Page<BillingOrderDto> dtoPage = orders.map(BillingOrderDto::fromEntity);
        return ResponseEntity.ok(PageResponse.of(dtoPage));
    }
}
