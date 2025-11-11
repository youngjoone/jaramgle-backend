package com.fairylearn.backend.service;

import com.fairylearn.backend.dto.ShareStoryResponse;
import com.fairylearn.backend.dto.SharedStoryDetailDto;
import com.fairylearn.backend.dto.SharedStorySummaryDto;
import com.fairylearn.backend.dto.StoryDto;
import com.fairylearn.backend.dto.StoryPageDto;
import com.fairylearn.backend.dto.StorybookPageDto;
import com.fairylearn.backend.entity.SharedStory;
import com.fairylearn.backend.entity.Story;
import com.fairylearn.backend.entity.StoryPage;
import com.fairylearn.backend.entity.StorybookPage;
import com.fairylearn.backend.repository.SharedStoryCommentRepository;
import com.fairylearn.backend.repository.SharedStoryLikeRepository;
import com.fairylearn.backend.repository.SharedStoryRepository;
import com.fairylearn.backend.repository.StoryPageRepository;
import com.fairylearn.backend.repository.StoryRepository;
import com.fairylearn.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final StoryService storyService;
    private final StorybookService storybookService;
    private final UserRepository userRepository;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    @Transactional
    public ShareStoryResponse shareStory(Long storyId, String userId) {
        Story story = storyRepository.findByIdAndUserId(storyId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Story not found or not owned by user."));

        SharedStory sharedStory = sharedStoryRepository.findByStoryId(story.getId())
                .map(existing -> updateTitleIfNeeded(existing, story.getTitle()))
                .orElseGet(() -> createSharedStory(story));

        String shareUrl = String.format("%s/shared/%s", frontendBaseUrl, sharedStory.getShareSlug());
        return new ShareStoryResponse(sharedStory.getShareSlug(), shareUrl);
    }

    @Transactional(readOnly = true)
    public Optional<String> findShareSlugForStory(Long storyId) {
        return sharedStoryRepository.findByStoryId(storyId).map(SharedStory::getShareSlug);
    }

    @Transactional(readOnly = true)
    public List<SharedStorySummaryDto> getSharedStories() {
        List<SharedStory> sharedStories = sharedStoryRepository.findAllByOrderByCreatedAtDesc();
        if (sharedStories.isEmpty()) {
            return List.of();
        }

        List<Long> sharedStoryIds = sharedStories.stream()
                .map(SharedStory::getId)
                .collect(Collectors.toList());

        Map<Long, Long> likeCounts = toCountMap(sharedStoryLikeRepository.countBySharedStoryIds(sharedStoryIds));
        Map<Long, Long> commentCounts = toCountMap(sharedStoryCommentRepository.countActiveCommentsBySharedStoryIds(sharedStoryIds));

        return sharedStories.stream()
                .map(shared -> new SharedStorySummaryDto(
                        shared.getShareSlug(),
                        shared.getSharedTitle(),
                        shared.getCreatedAt(),
                        buildPreview(shared.getStory()),
                        likeCounts.getOrDefault(shared.getId(), 0L),
                        commentCounts.getOrDefault(shared.getId(), 0L),
                        shared.getStory().getCoverImageUrl()
                ))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public SharedStoryDetailDto getSharedStoryBySlug(String slug, String viewerUserId) {
        SharedStory sharedStory = sharedStoryRepository.findByShareSlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("Shared story not found"));

        Story story = sharedStory.getStory();
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
        boolean likedByViewer = viewerNumericId != null && sharedStoryLikeRepository.existsBySharedStory_IdAndUser_Id(sharedStory.getId(), viewerNumericId);

        return new SharedStoryDetailDto(
                sharedStory.getShareSlug(),
                sharedStory.getSharedTitle(),
                sharedStory.getCreatedAt(),
                manageable,
                likeCount,
                likedByViewer,
                commentCount,
                storyDto
        );
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
        SharedStory sharedStory = sharedStoryRepository.findByShareSlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("Shared story not found"));
        Story story = sharedStory.getStory();
        return storyService.generateAudioForStory(story);
    }

    @Transactional
    public StorybookPageDto createStorybookForSharedStory(String slug) {
        SharedStory sharedStory = sharedStoryRepository.findByShareSlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("Shared story not found"));
        Story story = sharedStory.getStory();
        StorybookPage firstPage = storybookService.createStorybook(story.getId());
        return StorybookPageDto.fromEntity(firstPage);
    }

    @Transactional(readOnly = true)
    public List<StorybookPageDto> getStorybookPagesForSharedStory(String slug) {
        SharedStory sharedStory = sharedStoryRepository.findByShareSlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("Shared story not found"));
        List<StorybookPage> pages = storybookService.getStorybookPages(sharedStory.getStory().getId());
        return pages.stream()
                .map(StorybookPageDto::fromEntity)
                .collect(Collectors.toList());
    }
}
