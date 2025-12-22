package com.jaramgle.backend.filter;

import com.jaramgle.backend.auth.AuthPrincipal;
import com.jaramgle.backend.entity.User;
import com.jaramgle.backend.entity.UserStatus;
import com.jaramgle.backend.repository.UserRepository;
import com.jaramgle.backend.util.JwtProvider;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class CookieJwtAuthFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;

    private Optional<String> resolveToken(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        for (Cookie cookie : request.getCookies()) {
            if ("access_token".equals(cookie.getName())) {
                return Optional.ofNullable(cookie.getValue());
            }
        }
        // fallback to Authorization header
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return Optional.of(authHeader.substring(7));
        }
        return Optional.empty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        Optional<String> tokenOpt = resolveToken(request);
        if (tokenOpt.isPresent()) {
            String token = tokenOpt.get();
            try {
                Claims claims = jwtProvider.parse(token);
                Long userId = Long.valueOf(claims.getSubject());
                if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    Optional<User> userOpt = userRepository.findById(userId);
                    if (userOpt.isPresent()) {
                        User user = userOpt.get();
                        if (!user.isDeleted() && user.getStatus() == UserStatus.ACTIVE) {
                            String role = user.getRoleKey() != null ? user.getRoleKey() : "ROLE_USER";
                            String email = user.getEmail();
                            var authorities = List.of(new SimpleGrantedAuthority(role));
                            var principal = new AuthPrincipal(userId, email, authorities);
                            var auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                            SecurityContextHolder.getContext().setAuthentication(auth);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("JWT parsing failed: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }
}
