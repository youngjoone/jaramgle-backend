package com.findme.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class TestResponseDto {
    private String code;
    private String title;
    private List<QuestionDto> questions;
}
