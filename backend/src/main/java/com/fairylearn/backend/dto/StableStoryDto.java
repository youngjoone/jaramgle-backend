package com.fairylearn.backend.dto;

import java.util.List;

public record StableStoryDto(
    String title,
    List<StableStoryPageDto> pages,
    List<AiQuiz> quiz
) {}
