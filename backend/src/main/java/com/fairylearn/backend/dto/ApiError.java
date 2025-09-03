package com.fairylearn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiError {
    private String code;
    private String message;

    public static ApiError of(String code, String message) {
        return new ApiError(code, message);
    }
}
