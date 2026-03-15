package com.jaramgle.backend.controller;

import com.jaramgle.backend.auth.AuthPrincipal;
import com.jaramgle.backend.dto.AdminCurriculumOrphanCleanupRequest;
import com.jaramgle.backend.dto.AdminCurriculumOrphanCleanupResultDto;
import com.jaramgle.backend.dto.AdminCurriculumOrphanPreviewDto;
import com.jaramgle.backend.service.AdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/maintenance")
@RequiredArgsConstructor
public class AdminMaintenanceController {

    private final AdminService adminService;

    @GetMapping("/curriculum-orphans")
    public ResponseEntity<AdminCurriculumOrphanPreviewDto> previewCurriculumOrphans(
            @RequestParam(value = "olderThanMinutes", required = false) Integer olderThanMinutes,
            @RequestParam(value = "limit", required = false) Integer limit,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(adminService.previewCurriculumOrphans(principal.id(), olderThanMinutes, limit));
    }

    @PostMapping("/curriculum-orphans/cleanup")
    public ResponseEntity<AdminCurriculumOrphanCleanupResultDto> cleanupCurriculumOrphans(
            @Valid @RequestBody(required = false) AdminCurriculumOrphanCleanupRequest request,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(adminService.cleanupCurriculumOrphans(principal.id(), request));
    }
}
