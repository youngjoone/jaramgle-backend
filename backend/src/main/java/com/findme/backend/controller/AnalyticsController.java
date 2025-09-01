package com.findme.backend.controller;

import com.findme.backend.dto.AnalyticsEventDto;
import com.findme.backend.dto.AnalyticsSummaryDto;
import com.findme.backend.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @PostMapping("/batch")
    public ResponseEntity<Void> saveAnalyticsEvents(@RequestBody Map<String, List<AnalyticsEventDto>> requestBody) {
        List<AnalyticsEventDto> events = requestBody.get("items");
        if (events != null && !events.isEmpty()) {
            analyticsService.saveAnalyticsEvents(events);
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/summary")
    public ResponseEntity<AnalyticsSummaryDto> getAnalyticsSummary(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {

        LocalDate endDate = (to != null) ? to : LocalDate.now();
        LocalDate startDate = (from != null) ? from : endDate.minusDays(6); // Default to last 7 days

        AnalyticsSummaryDto summary = analyticsService.getSummary(startDate, endDate);
        return ResponseEntity.ok(summary);
    }
}
