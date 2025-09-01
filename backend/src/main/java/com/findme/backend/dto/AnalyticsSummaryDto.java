package com.findme.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsSummaryDto {
    private Map<String, Long> totals;
    private Map<String, Double> funnel;
}
