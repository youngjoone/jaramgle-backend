package com.jaramgle.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class AdjustHeartsRequest {
    @NotNull
    private Integer delta;
    private String reason;
    private Map<String, Object> metadata;
}
