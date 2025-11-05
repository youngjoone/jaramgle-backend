package com.fairylearn.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.PAYMENT_REQUIRED)
public class InsufficientHeartsException extends PaymentRequiredException {

    private final String code;

    public InsufficientHeartsException(String message) {
        this("INSUFFICIENT_HEARTS", message);
    }

    public InsufficientHeartsException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
