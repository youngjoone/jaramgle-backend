package com.findme.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class ResultListItemDto {
    private Long id;
    private String testCode;
    private double score;
    private LocalDateTime createdAt;
}
