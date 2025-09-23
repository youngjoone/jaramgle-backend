package com.fairylearn.backend.controller;

import com.fairylearn.backend.auth.AuthPrincipal;
import com.fairylearn.backend.dto.CommentLikeStatusDto;
import com.fairylearn.backend.dto.CreateSharedStoryCommentRequest;
import com.fairylearn.backend.dto.SharedStoryCommentDto;
import com.fairylearn.backend.dto.SharedStoryDetailDto;
import com.fairylearn.backend.dto.SharedStorySummaryDto;
import com.fairylearn.backend.dto.StoryLikeStatusDto;
import com.fairylearn.backend.dto.StorybookPageDto;
import com.fairylearn.backend.dto.UpdateSharedStoryCommentRequest;
import com.fairylearn.backend.service.SharedStoryInteractionService;
import com.fairylearn.backend.service.StoryShareService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
@Slf4j
public class SharedStoryPublicController {

    private final StoryShareService storyShareService;
    private final SharedStoryInteractionService sharedStoryInteractionService;

    @GetMapping("/shared-stories")
    public ResponseEntity<List<SharedStorySummaryDto>> listSharedStories() {
        return ResponseEntity.ok(storyShareService.getSharedStories());
    }

    @GetMapping("/shared-stories/{slug}")
    public ResponseEntity<SharedStoryDetailDto> getSharedStory(@PathVariable String slug,
                                                              @AuthenticationPrincipal AuthPrincipal principal) {
        try {
            String viewerId = principal != null ? String.valueOf(principal.id()) : null;
            return ResponseEntity.ok(storyShareService.getSharedStoryBySlug(slug, viewerId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/shared-stories/{slug}/audio")
    public ResponseEntity<String> generateSharedAudio(@PathVariable String slug) {
        try {
            String audioUrl = storyShareService.generateAudioForSharedStory(slug);
            return ResponseEntity.ok(audioUrl);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/shared-stories/{slug}/storybook")
    public ResponseEntity<StorybookPageDto> createSharedStorybook(@PathVariable String slug) {
        try {
            StorybookPageDto firstPage = storyShareService.createStorybookForSharedStory(slug);
            return ResponseEntity.ok(firstPage);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/shared-stories/{slug}/storybook/pages")
    public ResponseEntity<List<StorybookPageDto>> getSharedStorybookPages(@PathVariable String slug) {
        try {
            return ResponseEntity.ok(storyShareService.getStorybookPagesForSharedStory(slug));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/shared-stories/{slug}/likes")
    public ResponseEntity<?> toggleStoryLike(@PathVariable String slug,
                                             @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("message", "로그인이 필요합니다."));
        }
        try {
            StoryLikeStatusDto status = sharedStoryInteractionService.toggleStoryLike(slug, principal.id());
            return ResponseEntity.ok(status);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        } catch (IllegalStateException ex) {
            log.error("Failed to toggle story like for slug {}", slug, ex);
            return ResponseEntity.status(500).body(Map.of("message", ex.getMessage()));
        } catch (Exception ex) {
            log.error("Unexpected error while toggling story like for slug {}", slug, ex);
            return ResponseEntity.status(500).body(Map.of("message", "알 수 없는 오류가 발생했습니다."));
        }
    }

    @GetMapping("/shared-stories/{slug}/comments")
    public ResponseEntity<List<SharedStoryCommentDto>> getComments(@PathVariable String slug,
                                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        Long userId = principal != null ? principal.id() : null;
        List<SharedStoryCommentDto> comments = sharedStoryInteractionService.getComments(slug, userId);
        return ResponseEntity.ok(comments);
    }

    @PostMapping("/shared-stories/{slug}/comments")
    public ResponseEntity<?> createComment(@PathVariable String slug,
                                           @AuthenticationPrincipal AuthPrincipal principal,
                                           @RequestBody @Valid CreateSharedStoryCommentRequest request) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("message", "로그인이 필요합니다."));
        }
        try {
            SharedStoryCommentDto dto = sharedStoryInteractionService.createComment(slug, principal.id(), request);
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        } catch (IllegalStateException ex) {
            log.error("Failed to create comment for slug {}", slug, ex);
            return ResponseEntity.status(500).body(Map.of("message", ex.getMessage()));
        } catch (Exception ex) {
            log.error("Unexpected error while creating comment for slug {}", slug, ex);
            return ResponseEntity.status(500).body(Map.of("message", "알 수 없는 오류가 발생했습니다."));
        }
    }

    @PatchMapping("/shared-stories/{slug}/comments/{commentId}")
    public ResponseEntity<?> updateComment(@PathVariable String slug,
                                           @PathVariable Long commentId,
                                           @AuthenticationPrincipal AuthPrincipal principal,
                                           @RequestBody @Valid UpdateSharedStoryCommentRequest request) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("message", "로그인이 필요합니다."));
        }
        try {
            SharedStoryCommentDto dto = sharedStoryInteractionService.updateComment(commentId, principal.id(), request);
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        } catch (Exception ex) {
            log.error("Unexpected error while updating comment {}", commentId, ex);
            return ResponseEntity.status(500).body(Map.of("message", "알 수 없는 오류가 발생했습니다."));
        }
    }

    @DeleteMapping("/shared-stories/{slug}/comments/{commentId}")
    public ResponseEntity<?> deleteComment(@PathVariable String slug,
                                           @PathVariable Long commentId,
                                           @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("message", "로그인이 필요합니다."));
        }
        try {
            sharedStoryInteractionService.deleteComment(commentId, principal.id());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        } catch (Exception ex) {
            log.error("Unexpected error while deleting comment {}", commentId, ex);
            return ResponseEntity.status(500).body(Map.of("message", "알 수 없는 오류가 발생했습니다."));
        }
    }

    @PostMapping("/shared-stories/{slug}/comments/{commentId}/likes")
    public ResponseEntity<?> toggleCommentLike(@PathVariable String slug,
                                               @PathVariable Long commentId,
                                               @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("message", "로그인이 필요합니다."));
        }
        try {
            CommentLikeStatusDto dto = sharedStoryInteractionService.toggleCommentLike(commentId, principal.id());
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        } catch (Exception ex) {
            log.error("Unexpected error while toggling comment like {}", commentId, ex);
            return ResponseEntity.status(500).body(Map.of("message", "알 수 없는 오류가 발생했습니다."));
        }
    }
}
