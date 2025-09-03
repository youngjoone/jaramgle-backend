package com.fairylearn.backend.service.stabilization;

import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class SentenceSplitterKo {

    private static final String SENTENCE_SPLIT_REGEX = "(?<=[.!?][\"']?)\\s+";

    public List<String> split(String text) {
        if (text == null || text.trim().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(text.split(SENTENCE_SPLIT_REGEX))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
