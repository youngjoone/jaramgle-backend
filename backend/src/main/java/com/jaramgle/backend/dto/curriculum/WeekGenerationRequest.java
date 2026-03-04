package com.jaramgle.backend.dto.curriculum;

import lombok.Data;

import java.util.List;

@Data
public class WeekGenerationRequest {

    private List<Long> characterIds;

    private String artStyle;

    private String voicePreset;
}
