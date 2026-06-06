package com.jaramgle.backend.exception;

import org.springframework.http.HttpStatus;

public class AiProviderException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public AiProviderException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
