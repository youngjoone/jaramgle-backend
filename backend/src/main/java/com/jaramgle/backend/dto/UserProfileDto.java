package com.jaramgle.backend.dto;

import java.time.LocalDateTime;

public record UserProfileDto(
        Long id,
        String email,
        String nickname,
        String provider,
        String role,
        LocalDateTime createdAt
) {}
