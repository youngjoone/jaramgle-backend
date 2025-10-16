package com.fairylearn.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CharacterModelingResponseDto(
        String imageUrl,
        String modelingStatus,
        Map<String, Object> metadata
) { }
