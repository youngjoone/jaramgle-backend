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
import com.fairylearn.backend.repository.SharedStoryRepository;
import com.fairylearn.backend.repository.StoryPageRepository;
import com.fairylearn.backend.repository.StoryRepository;
import com.fairylearn.backend.service.StorybookService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StoryShareService {

    private final StoryRepository storyRepository;
    private final StoryPageRepository storyPageRepository;
    private final SharedStoryRepository sharedStoryRepository;
    private final StoryService storyService;
    private final StorybookService storybookService;

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
        return sharedStoryRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(shared -> new SharedStorySummaryDto(
                        shared.getShareSlug(),
                        shared.getSharedTitle(),
                        shared.getCreatedAt(),
                        buildPreview(shared.getStory())
                ))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public SharedStoryDetailDto getSharedStoryBySlug(String slug, String viewerUserId) {
        SharedStory sharedStory = sharedStoryRepository.findByShareSlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("Shared story not found"));

        Story story = sharedStory.getStory();
        List<StoryPageDto> pages = storyPageRepository.findByStoryIdOrderByPageNoAsc(story.getId()).stream()
                .map(StoryPageDto::fromEntity)
                .collect(Collectors.toList());

        StoryDto storyDto = StoryDto.fromEntityWithPages(story, pages);
        storyDto.setShareSlug(sharedStory.getShareSlug());
        storyDto.setSharedAt(sharedStory.getCreatedAt());
        boolean manageable = viewerUserId != null && viewerUserId.equals(story.getUserId());
        storyDto.setManageable(manageable);
        storyDto.setFullAudioUrl(story.getFullAudioUrl());

        return new SharedStoryDetailDto(
                sharedStory.getShareSlug(),
                sharedStory.getSharedTitle(),
                sharedStory.getCreatedAt(),
                manageable,
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
