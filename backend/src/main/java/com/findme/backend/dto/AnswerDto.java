package com.findme.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AnswerDto {
    @NotBlank
    private String questionId;

    @Min(1)
    @Max(5)
    private int value;
}
