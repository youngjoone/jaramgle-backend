package com.fairylearn.backend.controller;

import com.fairylearn.backend.auth.CustomOAuth2User;
import com.fairylearn.backend.dto.StoryDto;
import com.fairylearn.backend.dto.StoryPageDto;
import com.fairylearn.backend.dto.StorySaveRequest;
import com.fairylearn.backend.dto.StoryGenerateRequest; // Import StoryGenerateRequest
import com.fairylearn.backend.dto.StorageQuotaDto;
import com.fairylearn.backend.entity.Story;
import com.fairylearn.backend.service.StoryService;
import com.fairylearn.backend.service.StorageQuotaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StoryController {

    private final StoryService storyService;
    private final StorageQuotaService storageQuotaService;

    @GetMapping("/storage/me")
    public ResponseEntity<StorageQuotaDto> getMyStorageQuota(@AuthenticationPrincipal CustomOAuth2User principal) {
        StorageQuotaDto quota = StorageQuotaDto.fromEntity(storageQuotaService.getQuotaInfo(String.valueOf(principal.getId())));
        return ResponseEntity.ok(quota);
    }

    @GetMapping("/stories")
    public ResponseEntity<List<StoryDto>> getMyStories(@AuthenticationPrincipal CustomOAuth2User principal) {
        List<Story> stories = storyService.getStoriesByUserId(String.valueOf(principal.getId()));
        List<StoryDto> storyDtos = stories.stream()
                .map(StoryDto::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(storyDtos);
    }

    @GetMapping("/stories/{id}")
    public ResponseEntity<StoryDto> getStoryDetail(@PathVariable Long id, @AuthenticationPrincipal CustomOAuth2User principal) {
        return storyService.getStoryByIdAndUserId(id, String.valueOf(principal.getId()))
                .map(story -> {
                    List<StoryPageDto> pages = storyService.getStoryPagesByStoryId(story.getId()).stream()
                            .map(StoryPageDto::fromEntity)
                            .collect(Collectors.toList());
                    return ResponseEntity.ok(StoryDto.fromEntityWithPages(story, pages));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // Endpoint for story generation (as per 02_GENERATION_TASK.md)
    @PostMapping("/stories")
    public ResponseEntity<StoryDto> generateStory(@Valid @RequestBody StoryGenerateRequest request, @AuthenticationPrincipal CustomOAuth2User principal) {
        Story newStory = storyService.generateAndSaveStory(String.valueOf(principal.getId()), request);
        return new ResponseEntity<>(StoryDto.fromEntity(newStory), HttpStatus.CREATED);
    }

    // Endpoint for saving an existing story (e.g., from a wizard or import)
    // Renamed from createStory and changed path to avoid conflict with generation endpoint
    @PostMapping("/stories/save") // New path for saving existing stories
    public ResponseEntity<StoryDto> saveExistingStory(@Valid @RequestBody StorySaveRequest request, @AuthenticationPrincipal CustomOAuth2User principal) {
        Story newStory = storyService.saveNewStory(
                String.valueOf(principal.getId()),
                request.getTitle(),
                request.getAgeRange(),
                String.join(",", request.getTopics()), // Simple join for now
                request.getLanguage(),
                request.getLengthLevel(),
                request.getPageTexts()
        );
        return new ResponseEntity<>(StoryDto.fromEntity(newStory), HttpStatus.CREATED);
    }

    @DeleteMapping("/stories/{id}")
    public ResponseEntity<Void> deleteStory(@PathVariable Long id, @AuthenticationPrincipal CustomOAuth2User principal) {
        storyService.deleteStory(id, String.valueOf(principal.getId()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/stories/{id}/audio")
    public ResponseEntity<String> generateAudio(@PathVariable Long id, @AuthenticationPrincipal CustomOAuth2User principal) {
        String audioUrl = storyService.generateAudio(id, String.valueOf(principal.getId()));
        return ResponseEntity.ok(audioUrl);
    }
}