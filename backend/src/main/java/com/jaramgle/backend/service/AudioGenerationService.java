package com.jaramgle.backend.service;

import com.jaramgle.backend.dto.GenerateParagraphAudioRequestDto;
import com.jaramgle.backend.dto.GenerateParagraphAudioResponseDto;
import com.jaramgle.backend.entity.StorybookPage;
import com.jaramgle.backend.repository.StorybookPageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AudioGenerationService {

    private final StorybookPageRepository storybookPageRepository;
    private final WebClient webClient;

    @Async
    public void generateAudioForAllPagesAsync(Long storyId) {
        try {
            List<StorybookPage> pages = storybookPageRepository.findByStoryIdOrderByPageNumberAsc(storyId);
            for (StorybookPage page : pages) {
                try {
                    GenerateParagraphAudioRequestDto req = new GenerateParagraphAudioRequestDto();
                    req.setForceRegenerate(false);
                    req.setVoicePreset(null);
                    generatePageAudio(storyId, page.getId(), req);
                } catch (Exception ex) {
                    log.warn("Failed to generate audio for storyId={}, pageId={}: {}", storyId, page.getId(),
                            ex.getMessage());
                }
            }
            log.info("Completed async audio generation for storyId={}", storyId);
        } catch (Exception ex) {
            log.warn("Async audio generation failed for storyId={}: {}", storyId, ex.getMessage());
        }
    }

    @Transactional
    public StorybookPage generatePageAudio(Long storyId,
            Long pageId,
            GenerateParagraphAudioRequestDto requestDto) {
        StorybookPage page = storybookPageRepository.findByIdAndStoryId(pageId, storyId)
                .orElseThrow(() -> new IllegalArgumentException("Storybook page not found for this story."));
        if (page.getStory() != null && page.getStory().isDeleted()) {
            throw new IllegalArgumentException("Deleted stories cannot generate audio.");
        }

        if (!requestDto.isForceRegenerate() && StringUtils.hasText(page.getAudioUrl())) {
            log.info("Audio already exists for storyId={}, pageId={}; returning cached audio.", storyId, pageId);
            return page;
        }

        String resolvedText = StringUtils.hasText(requestDto.getText()) ? requestDto.getText() : page.getText();
        if (!StringUtils.hasText(resolvedText)) {
            throw new IllegalArgumentException("Paragraph text is required to generate audio.");
        }

        GenerateParagraphAudioRequestDto outbound = new GenerateParagraphAudioRequestDto();
        outbound.setStoryId(String.valueOf(storyId));
        outbound.setPageId(String.valueOf(pageId));
        outbound.setParagraphId(requestDto.getParagraphId());
        outbound.setSpeakerSlug(requestDto.getSpeakerSlug());
        outbound.setEmotion(requestDto.getEmotion());
        outbound.setStyleHint(requestDto.getStyleHint());
        outbound.setLanguage(requestDto.getLanguage());
        outbound.setForceRegenerate(requestDto.isForceRegenerate());
        outbound.setText(resolvedText);

        log.info("Requesting paragraph audio generation for storyId={}, pageId={} (forceRegenerate={})",
                storyId, pageId, requestDto.isForceRegenerate());

        GenerateParagraphAudioResponseDto response = webClient.post()
                .uri("/ai/generate-page-audio")
                .bodyValue(outbound)
                .retrieve()
                .bodyToMono(GenerateParagraphAudioResponseDto.class)
                .block();

        if (response == null || !StringUtils.hasText(response.getUrl())) {
            throw new RuntimeException("Failed to receive audio URL from AI service.");
        }

        String audioUrl = toWebAccessibleUrl(response.getUrl());
        page.setAudioUrl(audioUrl);
        StorybookPage saved = storybookPageRepository.save(page);
        log.info("Saved audio for storyId={}, pageId={} at {}", storyId, pageId, audioUrl);
        return saved;
    }

    private String toWebAccessibleUrl(String providedUrl) {
        if (!StringUtils.hasText(providedUrl)) {
            return providedUrl;
        }
        if (providedUrl.startsWith("http://") || providedUrl.startsWith("https://")) {
            return providedUrl;
        }
        String normalized = providedUrl.startsWith("/") ? providedUrl : "/" + providedUrl;
        return "http://localhost:8080" + normalized;
    }
}
