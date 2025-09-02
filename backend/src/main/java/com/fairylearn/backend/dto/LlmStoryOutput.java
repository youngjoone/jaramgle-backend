package com.fairylearn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LlmStoryOutput {
    private String title;
    private List<StoryPageDto> pages;
    private List<QuizItemDto> quiz;
}