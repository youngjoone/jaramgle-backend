package com.findme.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor; // Add NoArgsConstructor

import java.util.Map;

@Data
@NoArgsConstructor // Add NoArgsConstructor
@AllArgsConstructor
public class ResultDto {
    private Long id; // Added id field
    private double score;
    private Map<String, Double> traits;
}