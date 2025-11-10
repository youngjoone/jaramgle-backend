package com.fairylearn.backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CharacterModelingResponseDto(
        @JsonAlias({"imageUrl", "image_url"}) String imageUrl,
        @JsonAlias({"modelingStatus", "modeling_status"}) String modelingStatus,
        Map<String, Object> metadata
) { }
