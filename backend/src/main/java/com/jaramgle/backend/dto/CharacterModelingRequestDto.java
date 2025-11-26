package com.jaramgle.backend.dto;

public record CharacterModelingRequestDto(
        String characterName,
        String slug,
        String descriptionPrompt,
        String existingImageUrl
) { }
