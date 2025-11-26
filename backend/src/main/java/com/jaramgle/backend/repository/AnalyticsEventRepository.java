package com.jaramgle.backend.repository;

import com.jaramgle.backend.entity.AnalyticsEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AnalyticsEventRepository extends JpaRepository<AnalyticsEvent, Long> {
    List<AnalyticsEvent> findByTsBetween(LocalDateTime start, LocalDateTime end);
}