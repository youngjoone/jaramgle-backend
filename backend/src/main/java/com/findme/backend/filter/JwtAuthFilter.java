package com.findme.backend.filter;

import com.findme.backend.util.JwtProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.AuthenticationException;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.SignatureException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SecurityException;

import java.io.IOException;
import java.util.ArrayList;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        String token = null;
        String subject = null;

        log.debug("Processing request for URI: {}", request.getRequestURI());

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("No Bearer token found in Authorization header for URI: {}", request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }

        token = authHeader.substring(7);
        log.debug("Bearer token found for URI: {}. Token: {}...", request.getRequestURI(), token.substring(0, Math.min(token.length(), 10)));

        try {
            subject = jwtProvider.extractSubject(token);
            log.debug("Subject extracted: {}", subject);

            if (!jwtProvider.validateToken(token)) {
                log.warn("JWT token validation failed for subject: {}", subject);
                throw new AuthenticationException("Invalid JWT token") {};
            }
        } catch (ExpiredJwtException e) {
            log.warn("Invalid JWT token for URI: {}. Error: JWT expired", request.getRequestURI());
            throw new AuthenticationException("Expired JWT token") {};
        } catch (MalformedJwtException | UnsupportedJwtException | IllegalArgumentException | SecurityException e) {
            log.warn("Invalid JWT token for URI: {}. Error: {}", request.getRequestURI(), e.getMessage());
            throw new AuthenticationException("Invalid JWT token") {};
        }

        if (subject != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            log.debug("JWT token is valid for subject: {}", subject);
            UserDetails userDetails = new User(subject, "", new ArrayList<>());
            UsernamePasswordAuthenticationToken authenticationToken =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authenticationToken);
            log.debug("Authentication set for subject: {}", subject);
        } else if (subject == null) {
            log.debug("Subject is null after extraction or token is invalid.");
        } else {
            log.debug("SecurityContext already has authentication for subject: {}", SecurityContextHolder.getContext().getAuthentication().getName());
        }
        filterChain.doFilter(request, response);
    }
}
