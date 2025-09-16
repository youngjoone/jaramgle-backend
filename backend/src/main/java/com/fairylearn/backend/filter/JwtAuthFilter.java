package com.fairylearn.backend.filter;

import com.fairylearn.backend.exception.JwtAuthenticationException; // Import custom exception
import com.fairylearn.backend.util.JwtProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.SignatureException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SecurityException;

import java.io.IOException;
import java.util.ArrayList; // Keep if still needed, otherwise remove
import com.fairylearn.backend.auth.AuthPrincipal; // New import
import org.springframework.security.core.authority.SimpleGrantedAuthority; // New import
import java.util.List; // New import
import io.jsonwebtoken.Claims; // New import

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

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        token = authHeader.substring(7);

        try {
            Claims claims = jwtProvider.parse(token);
            Long userId = Long.valueOf(claims.getSubject());
            String email = claims.get("email", String.class);
            String role = claims.get("roles", String.class);

            if (userId != null && email != null && role != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                var authorities = List.of(new SimpleGrantedAuthority(role));

                var principal = new AuthPrincipal(userId, email, authorities);
                var auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);

                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        } catch (ExpiredJwtException e) {
            log.warn("Invalid JWT token for URI: {}. Error: JWT expired", request.getRequestURI());
        } catch (MalformedJwtException | UnsupportedJwtException | IllegalArgumentException | SecurityException e) {
            log.warn("Invalid JWT token for URI: {}. Error: {}", request.getRequestURI(), e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
