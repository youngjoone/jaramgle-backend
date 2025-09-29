package com.fairylearn.backend.dto;

import java.util.List; // ADDED

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiStory(
    String title,
    @JsonAlias({"story", "content", "pages"})
    JsonNode pages,
    @JsonAlias("quiz")
    List<AiQuiz> quiz
) {}