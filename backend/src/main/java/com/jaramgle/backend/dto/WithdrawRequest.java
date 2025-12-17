package com.jaramgle.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record WithdrawRequest(
        @NotBlank String confirmation
) {
}
