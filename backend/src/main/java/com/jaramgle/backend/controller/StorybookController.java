package com.jaramgle.backend.controller;

import com.jaramgle.backend.auth.AuthPrincipal;
import com.jaramgle.backend.dto.GenerateParagraphAudioRequestDto;
import com.jaramgle.backend.dto.StorybookCreateRequest;
import com.jaramgle.backend.dto.StorybookPageDto;
import com.jaramgle.backend.entity.StorybookPage;
import com.jaramgle.backend.service.StoryService;
import com.jaramgle.backend.service.StorybookService;
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
public class StorybookController {

    private final StorybookService storybookService;
    private final StoryService storyService;

    @PostMapping("/stories/{id}/storybook")
    public ResponseEntity<StorybookPageDto> createStorybook(
            @PathVariable Long id,
            @RequestBody(required = false) StorybookCreateRequest request,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        if (principal == null || storyService.getStoryByIdAndUserId(id, String.valueOf(principal.id())).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        StorybookPage firstPage = storybookService.createStorybook(id,
                request != null ? request.getVoicePreset() : null);
        return new ResponseEntity<>(StorybookPageDto.fromEntity(firstPage), HttpStatus.CREATED);
    }

    @GetMapping("/stories/{id}/storybook/pages")
    public ResponseEntity<List<StorybookPageDto>> getStorybookPages(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        if (principal == null || storyService.getStoryByIdAndUserId(id, String.valueOf(principal.id())).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<StorybookPage> pages = storybookService.getStorybookPages(id);
        List<StorybookPageDto> dtos = pages.stream()
                .map(StorybookPageDto::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/stories/{storyId}/storybook/pages/{pageId}/audio")
    public ResponseEntity<StorybookPageDto> generatePageAudio(
            @PathVariable Long storyId,
            @PathVariable Long pageId,
            @RequestBody GenerateParagraphAudioRequestDto requestDto,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        if (principal == null
                || storyService.getStoryByIdAndUserId(storyId, String.valueOf(principal.id())).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        GenerateParagraphAudioRequestDto payload = requestDto == null ? new GenerateParagraphAudioRequestDto() : requestDto;
        payload.setStoryId(String.valueOf(storyId));
        payload.setPageId(String.valueOf(pageId));

        StorybookPage updatedPage = storybookService.generatePageAudio(storyId, pageId, payload);
        return ResponseEntity.ok(StorybookPageDto.fromEntity(updatedPage));
    }
}
