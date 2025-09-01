package com.findme.backend.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {

    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> getMyInfo(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));
        }
        String userId = authentication.getName(); // Subject of the JWT

        // For now, just return the userId.
        // If full user details (email, nickname) are needed,
        // we would inject UserRepository and fetch UserEntity here.
        return ResponseEntity.ok(Map.of(
                "id", userId,
                "email", userId + "@example.com", // Placeholder
                "nickname", "User " + userId // Placeholder
        ));
    }
}
