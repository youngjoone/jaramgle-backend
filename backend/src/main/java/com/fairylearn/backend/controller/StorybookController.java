package com.fairylearn.backend.controller;

import com.fairylearn.backend.dto.GenerateParagraphAudioRequestDto;
import com.fairylearn.backend.dto.StorybookPageDto;
import com.fairylearn.backend.entity.StorybookPage;
import com.fairylearn.backend.service.StorybookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StorybookController {

    private final StorybookService storybookService;

    @PostMapping("/stories/{id}/storybook")
    public ResponseEntity<StorybookPageDto> createStorybook(@PathVariable Long id) {
        StorybookPage firstPage = storybookService.createStorybook(id);
        return new ResponseEntity<>(StorybookPageDto.fromEntity(firstPage), HttpStatus.CREATED);
    }

    @GetMapping("/stories/{id}/storybook/pages")
    public ResponseEntity<List<StorybookPageDto>> getStorybookPages(@PathVariable Long id) {
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
            @RequestBody GenerateParagraphAudioRequestDto requestDto
    ) {
        GenerateParagraphAudioRequestDto payload = requestDto == null ? new GenerateParagraphAudioRequestDto() : requestDto;
        payload.setStoryId(String.valueOf(storyId));
        payload.setPageId(String.valueOf(pageId));

        StorybookPage updatedPage = storybookService.generatePageAudio(storyId, pageId, payload);
        return ResponseEntity.ok(StorybookPageDto.fromEntity(updatedPage));
    }
}
