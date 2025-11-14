package com.fairylearn.backend.controller;

import com.fairylearn.backend.auth.AuthPrincipal;
import com.fairylearn.backend.dto.StoryBulkDeleteRequest;
import com.fairylearn.backend.dto.StoryDto;
import com.fairylearn.backend.dto.StoryPageDto;
import com.fairylearn.backend.dto.StorySaveRequest;
import com.fairylearn.backend.dto.StoryGenerateRequest;
import com.fairylearn.backend.dto.StorageQuotaDto;
import com.fairylearn.backend.entity.Story;
import com.fairylearn.backend.dto.ShareStoryResponse;
import com.fairylearn.backend.service.StoryService;
import com.fairylearn.backend.service.StoryShareService;
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
    private final StoryShareService storyShareService;

    @GetMapping("/storage/me")
    public ResponseEntity<StorageQuotaDto> getMyStorageQuota(@AuthenticationPrincipal AuthPrincipal principal) {
        StorageQuotaDto quota = StorageQuotaDto.fromEntity(storageQuotaService.getQuotaInfo(String.valueOf(principal.id())));
        return ResponseEntity.ok(quota);
    }

    @GetMapping("/stories")
    public ResponseEntity<List<StoryDto>> getMyStories(@AuthenticationPrincipal AuthPrincipal principal) {
        List<Story> stories = storyService.getStoriesByUserId(String.valueOf(principal.id()));
        List<StoryDto> storyDtos = stories.stream()
                .map(story -> {
                    StoryDto dto = StoryDto.fromEntity(story);
                    storyShareService.findShareSlugForStory(story.getId()).ifPresent(dto::setShareSlug);
                    dto.setManageable(true);
                    return dto;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(storyDtos);
    }

    @GetMapping("/stories/{id}")
    public ResponseEntity<StoryDto> getStoryDetail(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        return storyService.getStoryByIdAndUserId(id, String.valueOf(principal.id()))
                .map(story -> {
                    List<StoryPageDto> pages = storyService.getStoryPagesByStoryId(story.getId()).stream()
                            .map(StoryPageDto::fromEntity)
                            .collect(Collectors.toList());
                    StoryDto dto = StoryDto.fromEntityWithPages(story, pages);
                    storyShareService.findShareSlugForStory(story.getId()).ifPresent(dto::setShareSlug);
                    dto.setManageable(true);
                    return ResponseEntity.ok(dto);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/stories")
    public ResponseEntity<StoryDto> generateStory(@Valid @RequestBody StoryGenerateRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        Story newStory = storyService.generateAndSaveStory(String.valueOf(principal.id()), request);
        StoryDto dto = StoryDto.fromEntity(newStory);
        dto.setManageable(true);
        return new ResponseEntity<>(dto, HttpStatus.CREATED);
    }

    @PostMapping("/stories/save")
    public ResponseEntity<StoryDto> saveExistingStory(@Valid @RequestBody StorySaveRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        Story newStory = storyService.saveNewStory(
                String.valueOf(principal.id()),
                request.getTitle(),
                request.getAgeRange(),
                String.join(",", request.getTopics()),
                request.getLanguage(),
                request.getLengthLevel(),
                request.getPageTexts(),
                request.getCharacterIds()
        );
        StoryDto dto = StoryDto.fromEntity(newStory);
        dto.setManageable(true);
        return new ResponseEntity<>(dto, HttpStatus.CREATED);
    }

    @DeleteMapping("/stories/{id}")
    public ResponseEntity<Void> deleteStory(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        storyService.deleteStory(id, String.valueOf(principal.id()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/stories/bulk-delete")
    public ResponseEntity<Void> bulkDeleteStories(
            @Valid @RequestBody StoryBulkDeleteRequest request,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        storyService.deleteStories(request.storyIds(), String.valueOf(principal.id()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/stories/{id}/audio")
    public ResponseEntity<String> generateAudio(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        String audioUrl = storyService.generateAudio(id, String.valueOf(principal.id()));
        return ResponseEntity.ok(audioUrl);
    }

    @PostMapping("/stories/{id}/share")
    public ResponseEntity<ShareStoryResponse> shareStory(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        ShareStoryResponse response = storyShareService.shareStory(id, String.valueOf(principal.id()));
        return ResponseEntity.ok(response);
    }
}
