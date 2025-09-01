package com.findme.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "analytics_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_name", nullable = false)
    private String eventName;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(columnDefinition = "CLOB") // For H2, TEXT in Postgres
    private String payload;

    @Column(name = "ts", nullable = false)
    private LocalDateTime ts;
}
