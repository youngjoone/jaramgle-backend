package com.jaramgle.backend.dto;

import com.jaramgle.backend.dto.CharacterVisualDto; // Import the top-level DTO
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GenerateImageRequestDto {
    private String text;
    private List<CharacterVisualDto> characters; // Changed to CharacterVisualDto
    private String artStyle;
    private List<CharacterVisualDto> characterVisuals;
}