package com.findme.backend;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import java.util.Collections;

@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, String> healthCheck() {
        return Collections.singletonMap("status", "ok");
    }
}
