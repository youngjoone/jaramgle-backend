package com.findme.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class ResultDetailDto {
    private Long id;
    private String testCode;
    private double score;
    private String traits; // JSON string
    private String poem;
    private LocalDateTime createdAt;
}
