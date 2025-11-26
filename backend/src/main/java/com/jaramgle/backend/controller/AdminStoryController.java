package com.jaramgle.backend.controller;

import com.jaramgle.backend.auth.AuthPrincipal;
import com.jaramgle.backend.dto.AdminSharedCommentDto;
import com.jaramgle.backend.dto.AdminStoryDto;
import com.jaramgle.backend.dto.PageResponse;
import com.jaramgle.backend.dto.UpdateSharedCommentAdminRequest;
import com.jaramgle.backend.dto.UpdateStoryAdminRequest;
import com.jaramgle.backend.service.AdminService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminStoryController {

    private final AdminService adminService;

    @GetMapping("/stories")
    public ResponseEntity<PageResponse<AdminStoryDto>> listStories(
            @RequestParam(value = "deleted", required = false) Boolean deleted,
            @RequestParam(value = "hidden", required = false) Boolean hidden,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(size, 100));
        var stories = adminService.listStories(deleted, hidden, userId, query, pageable);
        return ResponseEntity.ok(PageResponse.of(stories));
    }

    @PatchMapping("/stories/{storyId}")
    public ResponseEntity<AdminStoryDto> updateStory(
            @PathVariable Long storyId,
            @Valid @RequestBody UpdateStoryAdminRequest request,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(adminService.updateStory(principal.id(), storyId, request));
    }

    @GetMapping("/shared-stories/{slug}/comments")
    public ResponseEntity<List<AdminSharedCommentDto>> listComments(@PathVariable String slug) {
        return ResponseEntity.ok(adminService.listSharedComments(slug));
    }

    @PatchMapping("/shared-comments/{commentId}")
    public ResponseEntity<AdminSharedCommentDto> updateComment(
            @PathVariable Long commentId,
            @Valid @RequestBody UpdateSharedCommentAdminRequest request,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(adminService.updateSharedComment(principal.id(), commentId, request));
    }
}
