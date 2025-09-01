package com.fairylearn.backend.dto;

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
public class StorySaveRequest {
    @NotBlank
    @Size(max = 255)
    private String title;

    @Size(max = 50)
    private String ageRange;

    private List<String> topics; // Will be converted to JSON string

    @Size(max = 10)
    private String language;

    @Size(max = 50)
    private String lengthLevel;

    @NotNull
    @Size(min = 1)
    private List<String> pageTexts;
}