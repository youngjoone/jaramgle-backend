package com.fairylearn.backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ConfirmOrderRequest {

    @JsonProperty("payment_key")
    @JsonAlias("paymentKey")
    private String paymentKey;

    @JsonProperty("pg_provider")
    @JsonAlias("pgProvider")
    private String pgProvider;
}
