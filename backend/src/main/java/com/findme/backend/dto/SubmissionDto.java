package com.findme.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.NoArgsConstructor; // Add NoArgsConstructor

import java.util.List;

@Data
@NoArgsConstructor // Add NoArgsConstructor
public class SubmissionDto {
    @NotEmpty
    @Valid
    private List<AnswerDto> answers;

    private String poem;
    private int version; // Added version field
}
