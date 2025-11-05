package com.fairylearn.backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateOrderRequest {

    @NotBlank
    @JsonProperty("product_code")
    @JsonAlias("productCode")
    private String productCode;

    @Min(1)
    private int quantity = 1;
}
