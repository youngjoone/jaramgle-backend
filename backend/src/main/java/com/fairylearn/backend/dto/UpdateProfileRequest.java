package com.fairylearn.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @NotBlank(message = "Nickname must not be blank")
        @Size(max = 64, message = "Nickname must be at most 64 characters")
        String nickname
) {}
