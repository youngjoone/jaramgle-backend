package com.fairylearn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GenerateImageRequestDto {
    private String text;
    private List<CharacterImageRequestDto> characters; // Kept for now, but new fields are preferred
    private String artStyle; // ADDED
    private List<CharacterVisualDto> characterVisuals; // ADDED

    // New inner DTO for detailed visual descriptions
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CharacterVisualDto {
        private String name;
        private String visualDescription;
    }
}