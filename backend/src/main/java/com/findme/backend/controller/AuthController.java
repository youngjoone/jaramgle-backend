package com.findme.backend.controller;

import com.findme.backend.service.AuthService;
import com.findme.backend.dto.LoginRequest;
import com.findme.backend.dto.SignupRequest;
import com.findme.backend.entity.UserEntity;
import com.findme.backend.util.JwtProvider;
import com.findme.backend.entity.RefreshTokenEntity;
import com.findme.backend.repository.RefreshTokenRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    private final RefreshTokenRepository refreshTokenRepository; // Inject RefreshTokenRepository
    private final AuthService authService;

    @PostMapping("/signup")
    public ResponseEntity<Map<String, String>> signup(@Valid @RequestBody SignupRequest signupRequest) {
        authService.signup(signupRequest);
        Map<String, String> response = new HashMap<>();
        response.put("message", "OK");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@Valid @RequestBody LoginRequest loginRequest) {
        UserEntity user = authService.login(loginRequest);

        // Generate access token
        String accessToken = jwtProvider.generateToken(user.getEmail()); // Use user's email as subject

        // Generate refresh token
        String refreshToken = jwtProvider.generateRefreshToken(user.getEmail());
        LocalDateTime refreshTokenExpiresAt = jwtProvider.extractExpiration(refreshToken).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();

        // Save refresh token to DB
        RefreshTokenEntity refreshTokenEntity = new RefreshTokenEntity();
        refreshTokenEntity.setUserId(user.getEmail()); // Use user's email as userId
        refreshTokenEntity.setToken(refreshToken);
        refreshTokenEntity.setExpiresAt(refreshTokenExpiresAt);
        refreshTokenRepository.save(refreshTokenEntity);

        Map<String, String> responseBody = new HashMap<>();
        responseBody.put("accessToken", accessToken);
        responseBody.put("refreshToken", refreshToken);

        return ResponseEntity.ok(responseBody);
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refresh(@RequestBody Map<String, String> request) {
        String oldRefreshToken = request.get("refreshToken");

        if (oldRefreshToken == null || oldRefreshToken.isEmpty()) {
            return ResponseEntity.status(401).body(Collections.singletonMap("message", "Refresh token is missing"));
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
        String userId;
        try {
            userId = jwtProvider.extractSubject(oldRefreshToken);
            if (!jwtProvider.validateToken(oldRefreshToken)) {
                return ResponseEntity.status(401).body(Collections.singletonMap("message", "Invalid refresh token (JWT validation failed)"));
            }
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Collections.singletonMap("message", "Invalid refresh token (JWT parsing error)"));
        }

        // c) 새 access 발급
        String newAccessToken = jwtProvider.generateToken(userId);

        // d) (회전) 새 refresh 발급 + DB 저장
        String newRefreshToken = jwtProvider.generateRefreshToken(userId);
        LocalDateTime newRefreshTokenExpiresAt = jwtProvider.extractExpiration(newRefreshToken).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();

        RefreshTokenEntity newRefreshTokenEntity = new RefreshTokenEntity();
        newRefreshTokenEntity.setUserId(userId);
        newRefreshTokenEntity.setToken(newRefreshToken);
        newRefreshTokenEntity.setExpiresAt(newRefreshTokenExpiresAt);
        refreshTokenRepository.save(newRefreshTokenEntity);

        // e) 기존 refresh 레코드 revoked_at=now 로 업데이트
        storedRefreshToken.setRevokedAt(LocalDateTime.now());
        refreshTokenRepository.save(storedRefreshToken);

        // f) 응답: { access, refresh }
        Map<String, String> responseBody = new HashMap<>();
        responseBody.put("accessToken", newAccessToken);
        responseBody.put("refreshToken", newRefreshToken);

        return ResponseEntity.ok(responseBody);
    }
}
