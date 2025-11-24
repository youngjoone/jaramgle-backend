package com.fairylearn.backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Size;

public record UpdateCharacterRequest(
        @Size(max = 128)
        String name,

        @Size(max = 512)
        String persona,

        @Size(max = 256)
        String catchphrase,

        @Size(max = 512)
        @JsonAlias("promptKeywords")
        String promptKeywords,

        @Size(max = 1024)
        @JsonAlias("visualDescription")
        String visualDescription,

        @Size(max = 1500)
        @JsonAlias("descriptionPrompt")
        String descriptionPrompt,

        @Size(max = 128)
        @JsonAlias("artStyle")
        String artStyle,

        Boolean regenerateImage
) {
}
