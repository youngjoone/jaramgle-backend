package com.jaramgle.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.PAYMENT_REQUIRED)
public class PaymentRequiredException extends RuntimeException {
    private final String code;

    public PaymentRequiredException(String message) {
        this("PAYMENT_REQUIRED", message);
    }

    public PaymentRequiredException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
