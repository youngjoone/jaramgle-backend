package com.fairylearn.backend.controller;

import com.fairylearn.backend.dto.ApiError;
import com.fairylearn.backend.exception.BizException;
import com.fairylearn.backend.exception.PaymentRequiredException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice; // Changed to RestControllerAdvice
import com.fairylearn.backend.exception.JwtAuthenticationException; // Import JwtAuthenticationException
import org.springframework.web.reactive.function.client.WebClientResponseException; // Import WebClientResponseException

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice // Changed from ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Map<String, String>> handleBizException(BizException ex) {
        Map<String, String> response = new HashMap<>();
        response.put("code", ex.getCode());
        response.put("message", ex.getMessage());
        HttpStatus status = HttpStatus.BAD_REQUEST;
        if ("EMAIL_TAKEN".equals(ex.getCode())) {
            status = HttpStatus.CONFLICT;
        }
        return new ResponseEntity<>(response, status);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> {
            String fieldName = error.getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        return new ResponseEntity<>(errors, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(PaymentRequiredException.class)
    public ResponseEntity<ApiError> handlePaymentRequired(PaymentRequiredException ex) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(ApiError.of(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ApiError> handleWebClient(WebClientResponseException ex) {
        // 파이썬 LLM 400/422는 422로 승격, 5xx는 502 등으로 매핑
        HttpStatus status = switch (ex.getStatusCode().value()) {
            case 400, 422 -> HttpStatus.UNPROCESSABLE_ENTITY; // 422
            case 500, 502, 503 -> HttpStatus.BAD_GATEWAY;     // 502
            default -> HttpStatus.INTERNAL_SERVER_ERROR;       // 500
        };
        return ResponseEntity.status(status).body(ApiError.of("AI_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiError.of("INTERNAL_ERROR", "서버 오류"));
    }
}
