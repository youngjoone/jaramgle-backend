package com.fairylearn.backend;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final WebClient webClient;

    public HealthController(WebClient webClient) {
        this.webClient = webClient;
    }

    @GetMapping("/api/health")
    public Map<String, String> backendHealth() {
        return Collections.singletonMap("status", "ok");
    }

    @GetMapping("/api/health/ai")
    public Map<String, Object> aiHealthCheck() {
        Map<String, Object> status = new HashMap<>();
        status.put("backendStatus", "ok");
        status.put("healthy", true);

        try {
            Map<?, ?> aiResponse = webClient.get()
                    .uri("/ai/health")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(5));

            status.put("aiServiceStatus", "ok");
            status.put("aiServiceResponse", aiResponse);
        } catch (Exception ex) {
            status.put("aiServiceStatus", "error");
            status.put("aiServiceError", ex.getMessage());
            status.put("healthy", false);
        }

        return status;
    }
}
