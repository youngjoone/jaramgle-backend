package com.jaramgle.backend.service;

import com.jaramgle.backend.dto.ShareStoryResponse;
import com.jaramgle.backend.dto.SharedStoryDetailDto;
import com.jaramgle.backend.dto.SharedStorySummaryDto;
import com.jaramgle.backend.dto.StoryDto;
import com.jaramgle.backend.dto.StoryPageDto;
import com.jaramgle.backend.dto.StorybookPageDto;
import com.jaramgle.backend.entity.SharedStory;
import com.jaramgle.backend.entity.Story;
import com.jaramgle.backend.entity.StoryPage;
import com.jaramgle.backend.entity.StorybookPage;
import com.jaramgle.backend.repository.SharedStoryCommentRepository;
import com.jaramgle.backend.repository.SharedStoryLikeRepository;
import com.jaramgle.backend.repository.SharedStoryRepository;
import com.jaramgle.backend.repository.StoryPageRepository;
import com.jaramgle.backend.repository.StoryRepository;
import com.jaramgle.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StoryShareService {

    private final StoryRepository storyRepository;
    private final StoryPageRepository storyPageRepository;
    private final SharedStoryRepository sharedStoryRepository;
    private final SharedStoryLikeRepository sharedStoryLikeRepository;
    private final SharedStoryCommentRepository sharedStoryCommentRepository;
    private final com.jaramgle.backend.repository.SharedStoryBookmarkRepository sharedStoryBookmarkRepository; // Added
    private final StoryService storyService;
    private final StorybookService storybookService;
    private final UserRepository userRepository;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    @Transactional
    public ShareStoryResponse shareStory(Long storyId, String userId) {
        Story story = storyRepository.findByIdAndUserIdAndDeletedFalse(storyId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Story not found or not owned by user."));
        if (story.isHidden() || story.isDeleted()) {
            throw new IllegalArgumentException("Hidden or deleted stories cannot be shared.");
        }

        SharedStory sharedStory = sharedStoryRepository.findByStoryId(story.getId())
                .map(existing -> {
                    existing.setHidden(false); // 재공유 시 노출
                    return updateTitleIfNeeded(existing, story.getTitle());
                })
                .orElseGet(() -> createSharedStory(story));

        String shareUrl = String.format("%s/shared/%s", frontendBaseUrl, sharedStory.getShareSlug());
        return new ShareStoryResponse(sharedStory.getShareSlug(), shareUrl);
    }

    @Transactional(readOnly = true)
    public Optional<String> findShareSlugForStory(Long storyId) {
        return sharedStoryRepository.findByStoryId(storyId)
                .filter(shared -> !shared.isHidden())
                .map(SharedStory::getShareSlug);
    }

    @Transactional
    public void unshareStory(Long storyId, String userId) {
        Story story = storyRepository.findByIdAndUserIdAndDeletedFalse(storyId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Story not found or not owned by user."));

        SharedStory sharedStory = sharedStoryRepository.findByStoryId(story.getId())
                .orElseThrow(() -> new IllegalArgumentException("Story is not shared."));

        sharedStory.setHidden(true);
        sharedStoryRepository.save(sharedStory);
    }

    @Transactional(readOnly = true)
    public List<SharedStorySummaryDto> getSharedStories(String viewerUserId) {
        List<SharedStory> sharedStories = sharedStoryRepository.findAllByHiddenFalseOrderByCreatedAtDesc();
        if (sharedStories.isEmpty()) {
            return List.of();
        }

        List<Long> sharedStoryIds = sharedStories.stream()
                .map(SharedStory::getId)
                .collect(Collectors.toList());

        Map<Long, Long> likeCounts = toCountMap(sharedStoryLikeRepository.countBySharedStoryIds(sharedStoryIds));
        Map<Long, Long> commentCounts = toCountMap(
                sharedStoryCommentRepository.countActiveCommentsBySharedStoryIds(sharedStoryIds));
        Long viewerNumericId = parseUserId(viewerUserId);
        Set<Long> likedIds = (viewerNumericId != null)
                ? new HashSet<>(
                        sharedStoryLikeRepository.findLikedSharedStoryIdsByUser(sharedStoryIds, viewerNumericId))
                : Set.of();

        Set<Long> bookmarkedIds = new HashSet<>();
        if (viewerNumericId != null) {
            sharedStoryBookmarkRepository.findByUserId(viewerNumericId)
                    .forEach(b -> bookmarkedIds.add(b.getSharedStory().getId()));
        }

        return sharedStories.stream()
                .filter(shared -> shared.getStory() != null && !shared.getStory().isDeleted()
                        && !shared.getStory().isHidden())
                .map(shared -> new SharedStorySummaryDto(
                        shared.getShareSlug(),
                        shared.getSharedTitle(),
                        shared.getCreatedAt(),
                        buildPreview(shared.getStory()),
                        likeCounts.getOrDefault(shared.getId(), 0L),
                        likedIds.contains(shared.getId()),
                        commentCounts.getOrDefault(shared.getId(), 0L),
                        shared.getStory().getCoverImageUrl(),
                        bookmarkedIds.contains(shared.getId()))) // Added isBookmarked
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public SharedStoryDetailDto getSharedStoryBySlug(String slug, String viewerUserId) {
        SharedStory sharedStory = sharedStoryRepository.findByShareSlugAndHiddenFalse(slug)
                .orElseThrow(() -> new IllegalArgumentException("Shared story not found"));

        Story story = sharedStory.getStory();
        if (story.isDeleted() || story.isHidden()) {
            throw new IllegalArgumentException("Shared story not available");
        }
        story.getCharacters().size();
        List<StoryPageDto> pages = storyPageRepository.findByStoryIdOrderByPageNoAsc(story.getId()).stream()
                .map(StoryPageDto::fromEntity)
                .collect(Collectors.toList());

        StoryDto storyDto = StoryDto.fromEntityWithPages(story, pages);
        storyDto.setShareSlug(sharedStory.getShareSlug());
        storyDto.setSharedAt(sharedStory.getCreatedAt());
        boolean manageable = viewerUserId != null && viewerUserId.equals(story.getUserId());
        storyDto.setManageable(manageable);
        enrichStoryAuthorInfo(story, storyDto);

        Long viewerNumericId = parseUserId(viewerUserId);
        long likeCount = sharedStoryLikeRepository.countBySharedStory_Id(sharedStory.getId());
        long commentCount = sharedStoryCommentRepository.countBySharedStory_IdAndDeletedFalse(sharedStory.getId());
        boolean likedByViewer = viewerNumericId != null
                && sharedStoryLikeRepository.existsBySharedStory_IdAndUser_Id(sharedStory.getId(), viewerNumericId);

        List<StorybookPage> sbPages = storybookService.getStorybookPages(story.getId());
        List<StorybookPageDto> sbDtos = sbPages.stream()
                .map(StorybookPageDto::fromEntity)
                .collect(Collectors.toList());

        return new SharedStoryDetailDto(
                sharedStory.getShareSlug(),
                sharedStory.getSharedTitle(),
                sharedStory.getCreatedAt(),
                manageable,
                likeCount,
                likedByViewer,
                commentCount,
                storyDto,
                sbDtos);
    }

    private SharedStory updateTitleIfNeeded(SharedStory sharedStory, String latestTitle) {
        if (!sharedStory.getSharedTitle().equals(latestTitle)) {
            sharedStory.setSharedTitle(latestTitle);
        }
        return sharedStoryRepository.save(sharedStory);
    }

    private SharedStory createSharedStory(Story story) {
        SharedStory sharedStory = new SharedStory();
        sharedStory.setStory(story);
        sharedStory.setShareSlug(generateUniqueSlug());
        sharedStory.setSharedTitle(story.getTitle());
        sharedStory.setCreatedAt(LocalDateTime.now());
        sharedStory.setHidden(false);
        return sharedStoryRepository.save(sharedStory);
    }

    private String generateUniqueSlug() {
        String candidate;
        do {
            candidate = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        } while (sharedStoryRepository.findByShareSlug(candidate).isPresent());
        return candidate;
    }

    private String buildPreview(Story story) {
        List<StoryPage> pages = storyPageRepository.findByStoryIdOrderByPageNoAsc(story.getId());
        if (pages.isEmpty()) {
            return "";
        }
        String text = pages.get(0).getText();
        return text.length() > 120 ? text.substring(0, 117) + "..." : text;
    }

    private void enrichStoryAuthorInfo(Story story, StoryDto storyDto) {
        Long ownerId = parseUserId(story.getUserId());
        if (ownerId == null) {
            return;
        }
        userRepository.findById(ownerId).ifPresent(user -> {
            storyDto.setAuthorId(user.getId());
            storyDto.setAuthorNickname(user.getName());
        });
    }

    private Long parseUserId(String userId) {
        if (userId == null) {
            return null;
        }
        try {
            return Long.valueOf(userId);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Map<Long, Long> toCountMap(List<Object[]> rows) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            Long key = (Long) row[0];
            Long value = (Long) row[1];
            counts.put(key, value);
        }
        return counts;
    }

    @Transactional
    public String generateAudioForSharedStory(String slug) {
        SharedStory sharedStory = sharedStoryRepository.findByShareSlugAndHiddenFalse(slug)
                .orElseThrow(() -> new IllegalArgumentException("Shared story not found"));
        Story story = sharedStory.getStory();
        if (story.isDeleted() || story.isHidden()) {
            throw new IllegalArgumentException("Shared story not available");
        }
        return storyService.generateAudioForStory(story);
    }

    @Transactional
    public StorybookPageDto createStorybookForSharedStory(String slug) {
        SharedStory sharedStory = sharedStoryRepository.findByShareSlugAndHiddenFalse(slug)
                .orElseThrow(() -> new IllegalArgumentException("Shared story not found"));
        Story story = sharedStory.getStory();
        if (story.isDeleted() || story.isHidden()) {
            throw new IllegalArgumentException("Shared story not available");
        }
        StorybookPage firstPage = storybookService.createStorybook(story.getId());
        return StorybookPageDto.fromEntity(firstPage);
    }

    @Transactional(readOnly = true)
    public List<StorybookPageDto> getStorybookPagesForSharedStory(String slug) {
        SharedStory sharedStory = sharedStoryRepository.findByShareSlugAndHiddenFalse(slug)
                .orElseThrow(() -> new IllegalArgumentException("Shared story not found"));
        if (sharedStory.getStory().isDeleted() || sharedStory.getStory().isHidden()) {
            throw new IllegalArgumentException("Shared story not available");
        }
        List<StorybookPage> pages = storybookService.getStorybookPages(sharedStory.getStory().getId());
        return pages.stream()
                .map(StorybookPageDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public void bookmarkStory(String slug, String userId) {
        Long numericUserId = parseUserId(userId);
        if (numericUserId == null) {
            throw new IllegalArgumentException("User ID is required for bookmarking.");
        }
        SharedStory sharedStory = sharedStoryRepository.findByShareSlugAndHiddenFalse(slug)
                .orElseThrow(() -> new IllegalArgumentException("Shared story not found"));

        if (sharedStoryBookmarkRepository.existsByUserIdAndSharedStoryId(numericUserId, sharedStory.getId())) {
            return; // Already bookmarked
        }

        com.jaramgle.backend.entity.SharedStoryBookmark bookmark = new com.jaramgle.backend.entity.SharedStoryBookmark();
        bookmark.setUserId(numericUserId);
        bookmark.setSharedStory(sharedStory);
        sharedStoryBookmarkRepository.save(bookmark);
    }

    @Transactional
    public void unbookmarkStory(String slug, String userId) {
        Long numericUserId = parseUserId(userId);
        if (numericUserId == null) {
            throw new IllegalArgumentException("User ID is required for unbookmarking.");
        }
        SharedStory sharedStory = sharedStoryRepository.findByShareSlugAndHiddenFalse(slug)
                .orElseThrow(() -> new IllegalArgumentException("Shared story not found"));

        sharedStoryBookmarkRepository.deleteByUserIdAndSharedStoryId(numericUserId, sharedStory.getId());
    }
}
