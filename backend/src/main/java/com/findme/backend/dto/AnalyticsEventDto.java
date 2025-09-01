package com.findme.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsEventDto {
    private String eventName;
    private Map<String, Object> payload;
    private LocalDateTime ts;
    private String sessionId;
}
