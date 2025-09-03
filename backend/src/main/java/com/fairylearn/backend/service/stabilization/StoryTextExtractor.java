package com.fairylearn.backend.service.stabilization;

import com.fairylearn.backend.dto.AiStory;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class StoryTextExtractor {

    public String extractRawText(AiStory story) {
        if (story == null || story.pages() == null || story.pages().isNull()) {
            return "";
        }

        List<String> pageTexts = new ArrayList<>();
        JsonNode pagesNode = story.pages();

        if (pagesNode.isArray()) {
            for (JsonNode pageElement : pagesNode) {
                if (pageElement.isObject() && pageElement.has("text")) {
                    pageTexts.add(pageElement.get("text").asText());
                } else if (pageElement.isTextual()) {
                    pageTexts.add(pageElement.asText());
                }
            }
        } else if (pagesNode.isObject()) {
            // Check if this object contains a nested "pages" array
            if (pagesNode.has("pages") && pagesNode.get("pages").isArray()) {
                JsonNode nestedPages = pagesNode.get("pages");
                for (JsonNode pageElement : nestedPages) {
                    if (pageElement.isObject() && pageElement.has("text")) {
                        pageTexts.add(pageElement.get("text").asText());
                    } else if (pageElement.isTextual()) {
                        pageTexts.add(pageElement.asText());
                    }
                }
            } else {
                // Fallback: if it's an object but doesn't contain a nested "pages" array,
                // try to extract text from its direct textual fields (like a title).
                // This is less ideal but handles unexpected structures.
                pagesNode.fields().forEachRemaining(entry -> {
                    if (entry.getValue().isTextual()) {
                        pageTexts.add(entry.getValue().asText());
                    }
                });
            }
        } else if (pagesNode.isTextual()) {
            pageTexts.add(pagesNode.asText());
        }

        String combinedText = pageTexts.stream()
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining(" "));

        return normalizeText(combinedText);
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\s\\u00A0\\u200B]+", " ").trim();
    }
}