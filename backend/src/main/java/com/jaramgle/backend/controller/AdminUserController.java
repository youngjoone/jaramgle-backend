package com.jaramgle.backend.controller;

import com.jaramgle.backend.auth.AuthPrincipal;
import com.jaramgle.backend.dto.AdminUserDto;
import com.jaramgle.backend.dto.AdjustHeartsRequest;
import com.jaramgle.backend.dto.HeartTransactionDto;
import com.jaramgle.backend.dto.PageResponse;
import com.jaramgle.backend.dto.UpdateUserAdminRequest;
import com.jaramgle.backend.entity.UserStatus;
import com.jaramgle.backend.service.AdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminService adminService;

    @GetMapping
    public ResponseEntity<PageResponse<AdminUserDto>> listUsers(
            @RequestParam(value = "status", required = false) UserStatus status,
            @RequestParam(value = "deleted", required = false) Boolean deleted,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(size, 100));
        var users = adminService.listUsers(status, deleted, query, pageable);
        return ResponseEntity.ok(PageResponse.of(users));
    }

    @PatchMapping("/{userId}")
    public ResponseEntity<AdminUserDto> updateUser(
            @PathVariable Long userId,
            @Valid @RequestBody UpdateUserAdminRequest request,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(adminService.updateUser(principal.id(), userId, request));
    }

    @PostMapping("/{userId}/hearts")
    public ResponseEntity<HeartTransactionDto> adjustHearts(
            @PathVariable Long userId,
            @Valid @RequestBody AdjustHeartsRequest request,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(adminService.adjustHearts(principal.id(), userId, request));
    }
}
