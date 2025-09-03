package com.fairylearn.backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiPage(
    @JsonAlias({"page", "content", "sentence"})
    String text,
    @JsonAlias({"page_no", "pageNumber"})
    Integer pageNum
) {}
