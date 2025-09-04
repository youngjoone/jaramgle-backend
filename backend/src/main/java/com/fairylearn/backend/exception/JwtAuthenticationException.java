package com.fairylearn.backend.exception;

import org.springframework.security.core.AuthenticationException; // Import AuthenticationException

public class JwtAuthenticationException extends AuthenticationException { // Extend AuthenticationException
    public JwtAuthenticationException(String message) {
        super(message);
    }
}
