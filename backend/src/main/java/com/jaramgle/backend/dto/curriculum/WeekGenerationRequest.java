package com.jaramgle.backend.dto.curriculum;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WeekGenerationRequest {

    @JsonAlias({"characterIds", "character_ids"})
    private List<Long> characterIds;

    @JsonAlias({"artStyle", "art_style"})
    private String artStyle;

    @JsonAlias({"voicePreset", "voice_preset"})
    private String voicePreset;
}
