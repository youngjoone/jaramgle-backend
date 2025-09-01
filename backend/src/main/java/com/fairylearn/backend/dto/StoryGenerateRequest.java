package com.fairylearn.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoryGenerateRequest {
    private String title; // Optional, can be null

    @NotBlank
    @Size(max = 50)
    private String ageRange;

    @NotNull
    @Size(min = 1)
    private List<String> topics;

    @NotNull
    private List<String> objectives;

    @Min(4)
    private int minPages;

    @NotBlank
    @Size(max = 10)
    private String language; // e.g., "KO", "EN"
}