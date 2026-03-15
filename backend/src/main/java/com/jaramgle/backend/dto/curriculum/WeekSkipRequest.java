package com.jaramgle.backend.dto.curriculum;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WeekSkipRequest {
    @JsonAlias({"reason", "skip_reason"})
    private String reason;
}
