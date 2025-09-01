package com.findme.backend.service;

import com.findme.backend.dto.AnalyticsEventDto;
import com.findme.backend.dto.AnalyticsSummaryDto;
import com.findme.backend.entity.AnalyticsEvent;
import com.findme.backend.repository.AnalyticsEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final AnalyticsEventRepository analyticsEventRepository;
    private final ObjectMapper objectMapper; // For converting payload Map to JSON string

    @Transactional
    public void saveAnalyticsEvents(List<AnalyticsEventDto> events) {
        List<AnalyticsEvent> entities = events.stream().map(dto -> {
            String payloadJson = null;
            if (dto.getPayload() != null) {
                try {
                    payloadJson = objectMapper.writeValueAsString(dto.getPayload());
                } catch (JsonProcessingException e) {
                    // Log error, but proceed without payload if conversion fails
                    System.err.println("Error converting payload to JSON: " + e.getMessage());
                }
            }
            return new AnalyticsEvent(null, dto.getEventName(), dto.getSessionId(), payloadJson, dto.getTs());
        }).collect(Collectors.toList());
        analyticsEventRepository.saveAll(entities);
    }

    public AnalyticsSummaryDto getSummary(LocalDate from, LocalDate to) {
        LocalDateTime startOfDay = from.atStartOfDay();
        LocalDateTime endOfDay = to.atTime(LocalTime.MAX);

        List<AnalyticsEvent> events = analyticsEventRepository.findByTsBetween(startOfDay, endOfDay);

        // Aggregate totals
        Map<String, Long> totals = events.stream()
                .collect(Collectors.groupingBy(AnalyticsEvent::getEventName, Collectors.counting()));

        // Calculate funnel (example values for now)
        double startToSubmit = 0.0;
        double submitToGenerate = 0.0;
        double generateToShare = 0.0;

        Long testStartCount = totals.getOrDefault("test_start", 0L);
        Long testSubmitCount = totals.getOrDefault("test_submit", 0L);
        Long generateSuccessCount = totals.getOrDefault("generate_success", 0L);
        Long shareClickCount = totals.getOrDefault("share_click", 0L);

        if (testStartCount > 0) {
            startToSubmit = (double) testSubmitCount / testStartCount;
        }
        if (testSubmitCount > 0) {
            submitToGenerate = (double) generateSuccessCount / testSubmitCount;
        }
        if (generateSuccessCount > 0) {
            generateToShare = (double) shareClickCount / generateSuccessCount;
        }

        return new AnalyticsSummaryDto(totals, Map.of(
                "start_to_submit", startToSubmit,
                "submit_to_generate", submitToGenerate,
                "generate_to_share", generateToShare
        ));
    }
}
