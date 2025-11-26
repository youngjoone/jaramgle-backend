package com.jaramgle.backend.controller;

import com.jaramgle.backend.dto.AdminBillingOrderDto;
import com.jaramgle.backend.dto.PageResponse;
import com.jaramgle.backend.entity.BillingOrderStatus;
import com.jaramgle.backend.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/billing")
@RequiredArgsConstructor
public class AdminBillingQueryController {

    private final AdminService adminService;

    @GetMapping("/orders")
    public ResponseEntity<PageResponse<AdminBillingOrderDto>> listOrders(
            @RequestParam(value = "status", required = false) BillingOrderStatus status,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(size, 100));
        var orders = adminService.listBillingOrders(status, userId, pageable);
        return ResponseEntity.ok(PageResponse.of(orders));
    }
}
