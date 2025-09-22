package com.fairylearn.backend.controller;

import com.fairylearn.backend.auth.AuthPrincipal;
import com.fairylearn.backend.dto.SharedStoryDetailDto;
import com.fairylearn.backend.dto.SharedStorySummaryDto;
import com.fairylearn.backend.dto.StorybookPageDto;
import com.fairylearn.backend.service.StoryShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class SharedStoryPublicController {

    private final StoryShareService storyShareService;

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
}
