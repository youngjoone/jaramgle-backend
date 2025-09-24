package com.fairylearn.backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty; 


import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoryGenerateRequest {
    private String title;

    @NotBlank
    @Size(max = 50)
    @JsonProperty("age_range")
    @JsonAlias({"ageRange","age_range"})
    private String ageRange;

    @NotEmpty
    private List<String> topics;

    @NotEmpty
    private List<String> objectives;

    @NotNull
    @Min(10)
    @Max(20)
    @JsonProperty("min_pages")
    @JsonAlias({"minPages","min_pages"})
    private Integer minPages;

    @NotBlank
    @Size(max = 10)
    private String language;

    @JsonProperty("character_ids")
    @JsonAlias({"characterIds","character_ids"})
    @Size(max = 2)
    private List<Long> characterIds;
}
