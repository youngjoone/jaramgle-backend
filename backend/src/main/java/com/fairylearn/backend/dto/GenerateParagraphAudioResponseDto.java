package com.fairylearn.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class GenerateParagraphAudioResponseDto {

    @JsonProperty("filePath")
    private String filePath;

    private String url;

    private String provider;

    @JsonProperty("alreadyExisted")
    private boolean alreadyExisted;
}
