package com.jaramgle.backend.service;

import com.jaramgle.backend.dto.AdminBillingOrderDto;
import com.jaramgle.backend.dto.AdminCleanupCandidateDto;
import com.jaramgle.backend.dto.AdminCleanupFailureDto;
import com.jaramgle.backend.dto.AdminCurriculumOrphanCleanupRequest;
import com.jaramgle.backend.dto.AdminCurriculumOrphanCleanupResultDto;
import com.jaramgle.backend.dto.AdminCurriculumOrphanPreviewDto;
import com.jaramgle.backend.dto.AdminSharedCommentDto;
import com.jaramgle.backend.dto.AdminStoryDto;
import com.jaramgle.backend.dto.AdminUserDto;
import com.jaramgle.backend.dto.AdjustHeartsRequest;
import com.jaramgle.backend.dto.HeartTransactionDto;
import com.jaramgle.backend.dto.UpdateSharedCommentAdminRequest;
import com.jaramgle.backend.dto.UpdateStoryAdminRequest;
import com.jaramgle.backend.dto.UpdateUserAdminRequest;
import com.jaramgle.backend.entity.BillingOrder;
import com.jaramgle.backend.entity.BillingOrderStatus;
import com.jaramgle.backend.entity.SharedStory;
import com.jaramgle.backend.entity.SharedStoryComment;
import com.jaramgle.backend.entity.Story;
import com.jaramgle.backend.entity.StoryOrigin;
import com.jaramgle.backend.entity.User;
import com.jaramgle.backend.repository.BillingOrderRepository;
import com.jaramgle.backend.repository.SharedStoryCommentRepository;
import com.jaramgle.backend.repository.SharedStoryRepository;
import com.jaramgle.backend.repository.StoryRepository;
import com.jaramgle.backend.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminService {

    private static final int DEFAULT_ORPHAN_OLDER_THAN_MINUTES = 60;
    private static final int MIN_ORPHAN_OLDER_THAN_MINUTES = 30;
    private static final int MAX_ORPHAN_OLDER_THAN_MINUTES = 10080;
    private static final int DEFAULT_ORPHAN_LIMIT = 100;
    private static final int MAX_ORPHAN_LIMIT = 500;

    private final UserRepository userRepository;
    private final StoryRepository storyRepository;
    private final SharedStoryRepository sharedStoryRepository;
    private final SharedStoryCommentRepository sharedStoryCommentRepository;
    private final BillingOrderRepository billingOrderRepository;
    private final HeartWalletService heartWalletService;
    private final StoryService storyService;
    private final AdminAuditService adminAuditService;

    @Transactional(readOnly = true)
    public Page<AdminUserDto> listUsers(com.jaramgle.backend.entity.UserStatus status, Boolean deleted, String query, Pageable pageable) {
        return userRepository.searchForAdmin(status, deleted, query, pageable)
                .map(AdminUserDto::fromEntity);
    }

    @Transactional
    public AdminUserDto updateUser(Long adminUserId, Long userId, UpdateUserAdminRequest request) {
        User adminUser = requireAdmin(adminUserId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        if (request.getStatus() != null) {
            user.setStatus(request.getStatus());
        }
        if (request.getDeleted() != null) {
            user.setDeleted(request.getDeleted());
        }
        if (request.getRole() != null && !request.getRole().isBlank()) {
            user.setRole(request.getRole().trim());
        }

        User saved = userRepository.save(user);
        adminAuditService.record(adminUser, "UPDATE_USER", "USER", String.valueOf(userId), "Updated user flags/role");
        return AdminUserDto.fromEntity(saved);
    }

    @Transactional
    public HeartTransactionDto adjustHearts(Long adminUserId, Long userId, AdjustHeartsRequest request) {
        User adminUser = requireAdmin(adminUserId);
        if (request.getDelta() == null) {
            throw new IllegalArgumentException("delta is required");
        }
        Map<String, Object> meta = request.getMetadata() == null ? new HashMap<>() : new HashMap<>(request.getMetadata());
        meta.putIfAbsent("adminUserId", adminUserId);

        var tx = heartWalletService.adjustHearts(adminUserId, userId, request.getDelta(), request.getReason(), meta);
        adminAuditService.record(adminUser, "ADJUST_HEARTS", "USER", String.valueOf(userId), "delta=" + request.getDelta());
        return HeartTransactionDto.fromEntity(tx);
    }

    @Transactional(readOnly = true)
    public Page<AdminStoryDto> listStories(Boolean deleted, Boolean hidden, String userId, String query, Pageable pageable) {
        Page<Story> stories = storyRepository.searchForAdmin(deleted, hidden, userId, query, pageable);
        List<Long> storyIds = stories.stream().map(Story::getId).filter(Objects::nonNull).toList();
        Map<Long, SharedStory> shares = sharedStoryRepository.findByStoryIdIn(storyIds).stream()
                .collect(Collectors.toMap(shared -> shared.getStory().getId(), shared -> shared));

        return stories.map(story -> AdminStoryDto.fromEntity(story, shares.get(story.getId())));
    }

    @Transactional
    public AdminStoryDto updateStory(Long adminUserId, Long storyId, UpdateStoryAdminRequest request) {
        User adminUser = requireAdmin(adminUserId);
        Story story = storyRepository.findById(storyId)
                .orElseThrow(() -> new EntityNotFoundException("Story not found"));

        if (request.getDeleted() != null) {
            story.setDeleted(request.getDeleted());
            if (Boolean.TRUE.equals(request.getDeleted())) {
                story.setHidden(true);
            }
        }
        if (request.getHidden() != null) {
            story.setHidden(request.getHidden());
        }
        Story saved = storyRepository.save(story);

        sharedStoryRepository.findByStoryId(storyId).ifPresent(shared -> {
            if (saved.isDeleted() || saved.isHidden()) {
                shared.setHidden(true);
            } else if (request.getHidden() != null) {
                shared.setHidden(request.getHidden());
            }
            sharedStoryRepository.save(shared);
        });

        adminAuditService.record(adminUser, "UPDATE_STORY", "STORY", String.valueOf(storyId), "hidden/deleted updated");
        SharedStory shared = sharedStoryRepository.findByStoryId(storyId).orElse(null);
        return AdminStoryDto.fromEntity(saved, shared);
    }

    @Transactional(readOnly = true)
    public List<AdminSharedCommentDto> listSharedComments(String shareSlug) {
        SharedStory sharedStory = sharedStoryRepository.findByShareSlug(shareSlug)
                .orElseThrow(() -> new EntityNotFoundException("Shared story not found"));
        List<SharedStoryComment> comments = sharedStoryCommentRepository.findBySharedStory_IdOrderByCreatedAtAsc(sharedStory.getId());
        return comments.stream()
                .map(comment -> AdminSharedCommentDto.fromEntity(comment, shareSlug))
                .toList();
    }

    @Transactional
    public AdminSharedCommentDto updateSharedComment(Long adminUserId, Long commentId, UpdateSharedCommentAdminRequest request) {
        User adminUser = requireAdmin(adminUserId);
        SharedStoryComment comment = sharedStoryCommentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found"));

        if (request.getDeleted() != null) {
            boolean toDelete = request.getDeleted();
            comment.setDeleted(toDelete);
            if (toDelete) {
                comment.setContent("삭제된 댓글입니다.");
            }
        }
        SharedStoryComment saved = sharedStoryCommentRepository.save(comment);
        String slug = saved.getSharedStory() != null ? saved.getSharedStory().getShareSlug() : null;
        adminAuditService.record(adminUser, "UPDATE_COMMENT", "SHARED_COMMENT", String.valueOf(commentId), "deleted=" + request.getDeleted());
        return AdminSharedCommentDto.fromEntity(saved, slug);
    }

    @Transactional(readOnly = true)
    public Page<AdminBillingOrderDto> listBillingOrders(BillingOrderStatus status, Long userId, Pageable pageable) {
        return billingOrderRepository.searchForAdmin(status, userId, pageable)
                .map(order -> {
                    // 기본값이 null일 수 있는 컬럼 방어
                    if (order.getPricePerUnit() == 0 && order.getTotalAmount() == 0 && order.getQuantity() == 0) {
                        // nothing
                    }
                    return AdminBillingOrderDto.fromEntity(order);
                });
    }

    @Transactional(readOnly = true)
    public AdminCurriculumOrphanPreviewDto previewCurriculumOrphans(Long adminUserId, Integer olderThanMinutes, Integer limit) {
        requireAdmin(adminUserId);
        int normalizedOlderThanMinutes = normalizeOlderThanMinutes(olderThanMinutes);
        int normalizedLimit = normalizeLimit(limit);
        LocalDateTime createdBefore = LocalDateTime.now().minusMinutes(normalizedOlderThanMinutes);

        long totalCandidates = storyRepository.countOrphanCurriculumStories(StoryOrigin.CURRICULUM, createdBefore);
        List<Story> candidateStories = storyRepository.findOrphanCurriculumStoriesForCleanup(
                StoryOrigin.CURRICULUM,
                createdBefore,
                PageRequest.of(0, normalizedLimit)
        );

        List<AdminCleanupCandidateDto> candidates = candidateStories.stream()
                .map(this::toCleanupCandidate)
                .toList();

        return AdminCurriculumOrphanPreviewDto.builder()
                .olderThanMinutes(normalizedOlderThanMinutes)
                .limit(normalizedLimit)
                .totalCandidates(totalCandidates)
                .candidates(candidates)
                .build();
    }

    public AdminCurriculumOrphanCleanupResultDto cleanupCurriculumOrphans(
            Long adminUserId,
            AdminCurriculumOrphanCleanupRequest request
    ) {
        User adminUser = requireAdmin(adminUserId);
        int normalizedOlderThanMinutes = normalizeOlderThanMinutes(request == null ? null : request.getOlderThanMinutes());
        int normalizedLimit = normalizeLimit(request == null ? null : request.getLimit());
        LocalDateTime createdBefore = LocalDateTime.now().minusMinutes(normalizedOlderThanMinutes);

        List<Story> candidates = storyRepository.findOrphanCurriculumStoriesForCleanup(
                StoryOrigin.CURRICULUM,
                createdBefore,
                PageRequest.of(0, normalizedLimit)
        );

        List<Long> deletedStoryIds = new ArrayList<>();
        List<AdminCleanupFailureDto> failures = new ArrayList<>();

        for (Story candidate : candidates) {
            Long storyId = candidate.getId();
            try {
                storyService.deleteStory(storyId, candidate.getUserId());
                deletedStoryIds.add(storyId);
            } catch (Exception ex) {
                failures.add(AdminCleanupFailureDto.builder()
                        .storyId(storyId)
                        .message(summarizeFailure(ex))
                        .build());
            }
        }

        adminAuditService.record(
                adminUser,
                "CLEANUP_CURRICULUM_ORPHAN_STORIES",
                "STORY",
                "BATCH",
                "olderThanMinutes=" + normalizedOlderThanMinutes
                        + ", limit=" + normalizedLimit
                        + ", attempted=" + candidates.size()
                        + ", deleted=" + deletedStoryIds.size()
                        + ", failed=" + failures.size()
        );

        return AdminCurriculumOrphanCleanupResultDto.builder()
                .olderThanMinutes(normalizedOlderThanMinutes)
                .limit(normalizedLimit)
                .attemptedCount(candidates.size())
                .deletedCount(deletedStoryIds.size())
                .failedCount(failures.size())
                .deletedStoryIds(deletedStoryIds)
                .failures(failures)
                .build();
    }

    private AdminCleanupCandidateDto toCleanupCandidate(Story story) {
        return AdminCleanupCandidateDto.builder()
                .storyId(story.getId())
                .title(story.getTitle())
                .userId(story.getUserId())
                .language(story.getLanguage())
                .createdAt(story.getCreatedAt())
                .build();
    }

    private int normalizeOlderThanMinutes(Integer olderThanMinutes) {
        if (olderThanMinutes == null) {
            return DEFAULT_ORPHAN_OLDER_THAN_MINUTES;
        }
        return Math.max(MIN_ORPHAN_OLDER_THAN_MINUTES, Math.min(olderThanMinutes, MAX_ORPHAN_OLDER_THAN_MINUTES));
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_ORPHAN_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_ORPHAN_LIMIT));
    }

    private String summarizeFailure(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() <= 250 ? message : message.substring(0, 250);
    }

    private User requireAdmin(Long adminUserId) {
        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new EntityNotFoundException("Admin user not found"));
        if (admin.getRole() == null || !admin.getRole().contains("ADMIN")) {
            throw new IllegalStateException("Admin role required");
        }
        return admin;
    }
}
