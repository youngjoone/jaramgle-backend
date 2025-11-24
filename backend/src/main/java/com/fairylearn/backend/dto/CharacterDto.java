package com.fairylearn.backend.dto;

public record CharacterDto(
        Long id,
        String slug,
        String name,
        String persona,
        String catchphrase,
        String promptKeywords,
        String imageUrl,
        String visualDescription,
        String descriptionPrompt,
        String modelingStatus,
        String scope,
        String artStyle
) {
}
