package com.fairylearn.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class GenerateAudioRequestDto {

    private String language;
    private List<CharacterProfileDto> characters;

    @JsonProperty("readingPlan") // ai-python 서비스의 SynthesizeFromPlanRequest 필드명과 일치시킴
    private List<Map<String, Object>> readingPlan;

    // AudioPageDto is no longer needed

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