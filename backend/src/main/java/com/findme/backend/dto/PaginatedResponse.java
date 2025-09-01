package com.findme.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PaginatedResponse<T> {
    private List<T> items;
    private String nextCursor; // Can be page number, timestamp, etc.
}
