package com.fairylearn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class GenerateAudioRequestDto {

    private String title;
    private String language;
    private List<AudioPageDto> pages;
    private List<CharacterProfileDto> characters;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AudioPageDto {
        private int pageNo;
        private String text;
    }

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
