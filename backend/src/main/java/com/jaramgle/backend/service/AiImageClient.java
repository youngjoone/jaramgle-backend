package com.jaramgle.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AiImageClient {

    private static final int RETRY_ATTEMPTS = Math.max(
            0,
            Integer.parseInt(System.getenv().getOrDefault("AI_IMAGE_CLIENT_RETRY_ATTEMPTS", "0")));
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(Math.max(
            1,
            Long.parseLong(System.getenv().getOrDefault("AI_IMAGE_CLIENT_RETRY_BACKOFF_SECONDS", "2"))));

    private final WebClient webClient;

    public JsonNode generatePageAssets(ObjectNode payload) {
        return postJsonWithRetry(
                "/ai/generate-page-assets",
                payload,
                "AI asset generation service unavailable after retries."
        );
    }

    public JsonNode generateCoverImage(ObjectNode payload) {
        return postJsonWithRetry(
                "/ai/generate-cover-image",
                payload,
                "AI cover image generation service unavailable after retries."
        );
    }

    private JsonNode postJsonWithRetry(String uri, ObjectNode payload, String retryExhaustedMessage) {
        Mono<JsonNode> responseMono = webClient.post()
                .uri(uri)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class);

        if (RETRY_ATTEMPTS > 0) {
            responseMono = responseMono.retryWhen(Retry.backoff(RETRY_ATTEMPTS, RETRY_BACKOFF)
                    .filter(this::isRetryable)
                    .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) ->
                            new RuntimeException(retryExhaustedMessage, retrySignal.failure())));
        }
        return responseMono.block();
    }

    private boolean isRetryable(Throwable throwable) {
        if (!(throwable instanceof WebClientResponseException ex)) {
            return false;
        }
        return ex.getStatusCode().value() == 429;
    }
}
