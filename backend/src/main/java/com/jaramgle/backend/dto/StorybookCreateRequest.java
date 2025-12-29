package com.jaramgle.backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StorybookCreateRequest {
    /**
     * Optional voice preset to use when generating TTS for this storybook.
     * Example: "male", "female", "child", "grandpa", "grandma"
     */
    @JsonProperty("voice_preset")
    @JsonAlias("voicePreset")
    private String voicePreset;
}
