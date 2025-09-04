package com.fairylearn.backend.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j; // Import Slf4j

import java.io.IOException;

@Component
@Slf4j // Add Slf4j annotation
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, 
                         AuthenticationException authException) throws IOException, ServletException {
        log.warn("Authentication entry point triggered for URI: {}. Exception: {}", request.getRequestURI(), authException.getMessage()); // Add log
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 401 Unauthorized
        response.setContentType("application/json");
        response.getWriter().write("{\"code\": \"AUTH_REQUIRED\"}"); // Changed to match user's guide
    }
}
