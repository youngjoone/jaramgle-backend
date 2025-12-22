package com.jaramgle.backend.util;

import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.time.LocalDateTime;

public final class CookieUtil {

    private static final String COOKIE_SAME_SITE = System.getenv().getOrDefault("COOKIE_SAMESITE", "Lax");
    private static final boolean COOKIE_SECURE = Boolean.parseBoolean(
            System.getenv().getOrDefault("COOKIE_SECURE", "false"));

    private CookieUtil() {}

    public static ResponseCookie buildAccessCookie(String token, Duration duration) {
        long maxAgeSeconds = Math.max(0, duration.getSeconds());
        return ResponseCookie.from("access_token", token)
                .httpOnly(true)
                .secure(COOKIE_SECURE)
                .sameSite(COOKIE_SAME_SITE)
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }

    public static ResponseCookie buildRefreshCookie(String token, LocalDateTime expiresAt) {
        long maxAgeSeconds = Math.max(0, Duration.between(LocalDateTime.now(), expiresAt).getSeconds());
        return ResponseCookie.from("refresh_token", token)
                .httpOnly(true)
                .secure(COOKIE_SECURE)
                .sameSite(COOKIE_SAME_SITE)
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }

    public static ResponseCookie buildExpiredCookie(String name) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(COOKIE_SECURE)
                .sameSite(COOKIE_SAME_SITE)
                .path("/")
                .maxAge(0)
                .build();
    }
}
