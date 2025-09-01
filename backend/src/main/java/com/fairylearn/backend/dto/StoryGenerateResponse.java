package com.fairylearn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoryGenerateResponse {
    private String title;
    private List<StoryPageDto> pages; // Reusing StoryPageDto
    private List<QuizItemDto> quiz; // Optional
}