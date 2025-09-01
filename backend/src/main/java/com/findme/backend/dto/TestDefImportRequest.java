package com.findme.backend.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestDefImportRequest {
    private String code;
    private String title;
    private int version;
    private JsonNode questions; // Change to JsonNode
    private JsonNode scoring; // Change to JsonNode
}
