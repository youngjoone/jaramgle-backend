package com.findme.backend.dto;

import com.fasterxml.jackson.databind.JsonNode; // Import JsonNode
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestDefResponse {
    private Long id;
    private String code;
    private int version;
    private String title;
    private String status;
    private JsonNode questions; // Change to JsonNode
    private JsonNode scoring; // Change to JsonNode
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
