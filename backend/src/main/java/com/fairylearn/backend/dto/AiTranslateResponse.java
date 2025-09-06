package com.fairylearn.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class AiTranslateResponse {
    @JsonProperty("translated_texts")
    private List<String> translatedTexts;
}
