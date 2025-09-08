package com.fairylearn.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class GenerateImageResponseDto {
    @JsonProperty("file_path")
    private String filePath;
}
