package com.fairylearn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class GenerateAudioFromStoryRequestDto {

    private String storyText;
    private List<CharacterProfileDto> characters;
    private String language;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CharacterProfileDto {
        private Long id;
        private String slug;
        private String name;
        private String persona;
        private String catchphrase;
        private String promptKeywords;
        private String imagePath;
    }
}
