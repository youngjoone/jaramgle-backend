package com.fairylearn.backend.service.stabilization;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class PageBuilder {
    private static final int MAX_PAGE_LENGTH = 150;

    public List<String> build(List<String> sentences, int minPages) {
        if (sentences == null || sentences.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> pages = new ArrayList<>();
        StringBuilder currentPage = new StringBuilder();

        for (String sentence : sentences) {
            if (sentence.length() > MAX_PAGE_LENGTH) {
                if (currentPage.length() > 0) {
                    pages.add(currentPage.toString());
                    currentPage.setLength(0);
                }
                pages.addAll(softBreak(sentence));
                continue;
            }

            if (currentPage.length() > 0 && (currentPage.length() + 1 + sentence.length()) > MAX_PAGE_LENGTH) {
                pages.add(currentPage.toString());
                currentPage.setLength(0);
            }

            if (currentPage.length() > 0) {
                currentPage.append(" ");
            }
            currentPage.append(sentence);
        }

        if (currentPage.length() > 0) {
            pages.add(currentPage.toString());
        }

        return pages;
    }

    private List<String> softBreak(String longSentence) {
        List<String> result = new ArrayList<>();
        String remaining = longSentence;
        while (remaining.length() > MAX_PAGE_LENGTH) {
            int breakPoint = remaining.lastIndexOf(' ', MAX_PAGE_LENGTH);
            if (breakPoint == -1) {
                breakPoint = MAX_PAGE_LENGTH;
            }
            result.add(remaining.substring(0, breakPoint).trim());
            remaining = remaining.substring(breakPoint).trim();
        }
        if (remaining.length() > 0) {
            result.add(remaining);
        }
        return result;
    }
}