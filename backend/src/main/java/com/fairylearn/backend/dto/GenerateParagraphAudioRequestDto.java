package com.fairylearn.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class GenerateParagraphAudioRequestDto {

    @JsonProperty("storyId")
    private String storyId;

    @JsonProperty("pageId")
    private String pageId;

    private String text;

    @JsonProperty("paragraphId")
    private String paragraphId;

    @JsonProperty("speakerSlug")
    private String speakerSlug;

    private String emotion;

    @JsonProperty("styleHint")
    private String styleHint;

    private String language;

    @JsonProperty("forceRegenerate")
    private boolean forceRegenerate;
}
