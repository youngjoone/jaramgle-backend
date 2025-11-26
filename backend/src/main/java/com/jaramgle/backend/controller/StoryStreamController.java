package com.jaramgle.backend.controller;

import com.jaramgle.backend.auth.CustomOAuth2User;
import com.jaramgle.backend.dto.StoryGenerateRequest;
import com.jaramgle.backend.service.StoryStreamService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StoryStreamController {

    private final StoryStreamService storyStreamService;

    @PostMapping(value = "/stories/stream", produces = "text/event-stream")
    public SseEmitter generateStoryStream(@Valid @RequestBody StoryGenerateRequest request, @AuthenticationPrincipal CustomOAuth2User principal) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 minute timeout

        storyStreamService.streamStory(emitter, String.valueOf(principal.getId()), request);

        return emitter;
    }
}
