package com.fairylearn.backend.controller;

import com.fairylearn.backend.repository.UserRepository;
import com.fairylearn.backend.service.AuthService;
import com.fairylearn.backend.dto.LoginRequest;
import com.fairylearn.backend.dto.SignupRequest;
import com.fairylearn.backend.entity.User;
import com.fairylearn.backend.util.JwtProvider;
import com.fairylearn.backend.entity.RefreshTokenEntity;
import com.fairylearn.backend.repository.RefreshTokenRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtProvider jwtProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthService authService;
    private final UserRepository userRepository; // Inject UserRepository
    private static final String REFRESH_COOKIE_NAME = "refresh_token";

    @PostMapping("/signup")
    public ResponseEntity<Map<String, String>> signup(@Valid @RequestBody SignupRequest signupRequest) {
        authService.signup(signupRequest);
        Map<String, String> response = new HashMap<>();
        response.put("message", "OK");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(
            @Valid @RequestBody LoginRequest loginRequest
    ) {
        User user = authService.login(loginRequest);

        // Generate access token using the User object (which uses ID as subject)
        String accessToken = jwtProvider.generateToken(user);

        // Generate refresh token using user ID as subject
        String refreshToken = jwtProvider.generateRefreshToken(String.valueOf(user.getId()));
        LocalDateTime refreshTokenExpiresAt = jwtProvider.extractExpiration(refreshToken).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();

        // Save refresh token to DB using user ID
        refreshTokenRepository.findByUserId(String.valueOf(user.getId()))
                .ifPresent(existing -> {
                    existing.setRevokedAt(LocalDateTime.now());
                    refreshTokenRepository.save(existing);
                });

        RefreshTokenEntity refreshTokenEntity = new RefreshTokenEntity();
        refreshTokenEntity.setUserId(String.valueOf(user.getId())); // Use user's ID as userId
        refreshTokenEntity.setToken(refreshToken);
        refreshTokenEntity.setExpiresAt(refreshTokenExpiresAt);
        refreshTokenRepository.save(refreshTokenEntity);

        Map<String, String> responseBody = new HashMap<>();
        responseBody.put("accessToken", accessToken);

        ResponseCookie refreshCookie = buildRefreshCookie(refreshToken, refreshTokenExpiresAt);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(responseBody);
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refresh(
            @CookieValue(value = REFRESH_COOKIE_NAME, required = false) String oldRefreshToken
    ) {
        if (oldRefreshToken == null || oldRefreshToken.isEmpty()) {
            return ResponseEntity.status(401).body(Collections.singletonMap("message", "Refresh token cookie is missing"));
        }

        // a) DB에서 token 조회 → 없거나 revoked_at!=null 또는 expires_at<now → 401
        Optional<RefreshTokenEntity> storedRefreshTokenOpt = refreshTokenRepository.findByToken(oldRefreshToken);
        if (storedRefreshTokenOpt.isEmpty()) {
            return ResponseEntity.status(401).body(Collections.singletonMap("message", "Invalid refresh token (not found)"));
        }
        RefreshTokenEntity storedRefreshToken = storedRefreshTokenOpt.get();

        if (storedRefreshToken.getRevokedAt() != null) {
            return ResponseEntity.status(401).body(Collections.singletonMap("message", "Invalid refresh token (revoked)"));
        }
        if (storedRefreshToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            return ResponseEntity.status(401).body(Collections.singletonMap("message", "Invalid refresh token (expired)"));
        }

        // b) JWT 검증(서명/만료) + sub 일치 확인
        String userIdStr;
        try {
            userIdStr = jwtProvider.extractSubject(oldRefreshToken);
            if (!jwtProvider.validateToken(oldRefreshToken)) {
                return ResponseEntity.status(401).body(Collections.singletonMap("message", "Invalid refresh token (JWT validation failed)"));
            }
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Collections.singletonMap("message", "Invalid refresh token (JWT parsing error)"));
        }

        // c) Find user in DB to create a full-featured token
        User user = userRepository.findById(Long.valueOf(userIdStr))
                .orElseThrow(() -> new UsernameNotFoundException("User not found with id: " + userIdStr));

        // d) Generate new access token with all claims
        String newAccessToken = jwtProvider.generateToken(user);

        // e) (Rotation) Generate new refresh token and save to DB
        String newRefreshToken = jwtProvider.generateRefreshToken(userIdStr);
        LocalDateTime newRefreshTokenExpiresAt = jwtProvider.extractExpiration(newRefreshToken).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();

        RefreshTokenEntity newRefreshTokenEntity = new RefreshTokenEntity();
        newRefreshTokenEntity.setUserId(userIdStr);
        newRefreshTokenEntity.setToken(newRefreshToken);
        newRefreshTokenEntity.setExpiresAt(newRefreshTokenExpiresAt);
        refreshTokenRepository.save(newRefreshTokenEntity);

        // f) Revoke old refresh token
        storedRefreshToken.setRevokedAt(LocalDateTime.now());
        refreshTokenRepository.save(storedRefreshToken);

        // g) Send new refresh cookie + respond with new access token
        Map<String, String> responseBody = new HashMap<>();
        responseBody.put("accessToken", newAccessToken);

        ResponseCookie refreshCookie = buildRefreshCookie(newRefreshToken, newRefreshTokenExpiresAt);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(responseBody);
    }

    private ResponseCookie buildRefreshCookie(String token, LocalDateTime expiresAt) {
        long maxAgeSeconds = Math.max(0, Duration.between(LocalDateTime.now(), expiresAt).getSeconds());
        return ResponseCookie.from(REFRESH_COOKIE_NAME, token)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @CookieValue(value = REFRESH_COOKIE_NAME, required = false) String currentRefreshToken
    ) {
        if (currentRefreshToken != null && !currentRefreshToken.isBlank()) {
            refreshTokenRepository.findByToken(currentRefreshToken)
                    .ifPresent(token -> {
                        token.setRevokedAt(LocalDateTime.now());
                        refreshTokenRepository.save(token);
                    });
        }

        ResponseCookie expiredCookie = ResponseCookie.from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredCookie.toString())
                .body(Collections.singletonMap("message", "LOGGED_OUT"));
    }
}
