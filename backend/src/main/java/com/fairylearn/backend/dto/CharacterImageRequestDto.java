package com.fairylearn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CharacterImageRequestDto {
    private Long id;
    private String slug;
    private String name;
    private String persona;
    private String catchphrase;
    private String promptKeywords;
    private String imagePath;
}
