package com.fairylearn.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiTranslateRequest {
    private List<String> texts;

    @JsonProperty("target_language")
    private String targetLanguage;
}
