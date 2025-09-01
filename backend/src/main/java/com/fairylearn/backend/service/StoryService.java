package com.fairylearn.backend.service;

import com.fairylearn.backend.entity.Story;
import com.fairylearn.backend.entity.StoryPage;
import com.fairylearn.backend.repository.StoryRepository;
import com.fairylearn.backend.repository.StoryPageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class StoryService {

    private final StoryRepository storyRepository;
    private final StoryPageRepository storyPageRepository;
    private final StorageQuotaService storageQuotaService; // Inject StorageQuotaService

    @Transactional(readOnly = true)
    public List<Story> getStoriesByUserId(String userId) {
        return storyRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Optional<Story> getStoryByIdAndUserId(Long storyId, String userId) {
        return storyRepository.findByIdAndUserId(storyId, userId);
    }

    @Transactional(readOnly = true)
    public List<StoryPage> getStoryPagesByStoryId(Long storyId) {
        return storyPageRepository.findByStoryIdOrderByPageNoAsc(storyId);
    }

    @Transactional
    public Story saveNewStory(String userId, String title, String ageRange, String topicsJson, String language, String lengthLevel, List<String> pageTexts) {
        // 1. Ensure slot is available
        storageQuotaService.ensureSlotAvailable(userId);

        // 2. Create and save Story
        Story story = new Story(null, userId, title, ageRange, topicsJson, language, lengthLevel, "DRAFT", LocalDateTime.now());
        story = storyRepository.save(story);

        // 3. Create and save StoryPages
        for (int i = 0; i < pageTexts.size(); i++) {
            StoryPage page = new StoryPage(null, story, i + 1, pageTexts.get(i));
            storyPageRepository.save(page);
        }

        // 4. Increase used count
        storageQuotaService.increaseUsedCount(userId);

        return story;
    }

    @Transactional
    public void deleteStory(Long storyId, String userId) {
        Optional<Story> storyOptional = storyRepository.findByIdAndUserId(storyId, userId);
        if (storyOptional.isPresent()) {
            storyRepository.delete(storyOptional.get());
            storageQuotaService.decreaseUsedCount(userId); // Decrease used count
        } else {
            throw new IllegalArgumentException("Story not found or not owned by user.");
        }
    }
}