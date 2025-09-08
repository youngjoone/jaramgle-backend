package com.fairylearn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GenerateImageRequestDto {
    private String text;
}
