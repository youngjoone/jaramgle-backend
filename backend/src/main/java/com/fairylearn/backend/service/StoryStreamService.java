package com.fairylearn.backend.service;

import com.fairylearn.backend.auth.CustomOAuth2User;
import com.fairylearn.backend.dto.StableStoryDto;
import com.fairylearn.backend.dto.StableStoryPageDto;
import com.fairylearn.backend.dto.StoryGenerateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StoryStreamService {

    private final StoryService storyService;
    private final StorageQuotaService storageQuotaService;

    @Async
    public void streamStory(SseEmitter emitter, String userId, StoryGenerateRequest request) {
        try {
            // 1. Check quota before starting generation
            storageQuotaService.ensureSlotAvailable(userId);

            // 2. Generate the story content by calling the refactored service method
            StableStoryDto stableStory = storyService.generateStableStoryDto(request);

            // 3. Send events
            List<StableStoryPageDto> pages = stableStory.pages();
            if (!pages.isEmpty()) {
                // Send first page immediately
                sendSseEvent(emitter, "pages_head", pages.subList(0, 1));

                // Send the rest of the pages
                if (pages.size() > 1) {
                    Thread.sleep(1000); // Simulate work
                    sendSseEvent(emitter, "pages_tail", pages.subList(1, pages.size()));
                }
            }

            Thread.sleep(500);
            sendSseEvent(emitter, "quiz", stableStory.quiz());

            // 4. Save the complete story. The quota increase is handled inside this method.
            storyService.saveGeneratedStory(userId, request, stableStory);

            // 5. Send 'done' event and complete
            sendSseEvent(emitter, "done", "Story generation complete");
            emitter.complete();

        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private void sendSseEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            // This can happen if the client disconnects. It's safe to ignore.
            // Log this for debugging if necessary.
        }
    }
}
