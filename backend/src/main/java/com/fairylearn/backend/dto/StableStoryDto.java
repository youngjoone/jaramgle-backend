package com.fairylearn.backend.dto;

import java.util.List;

public record StableStoryDto(
    String title,
    List<String> pages,
    List<AiQuiz> quiz
) {}