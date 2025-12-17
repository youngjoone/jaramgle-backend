package com.jaramgle.backend.controller;

import com.jaramgle.backend.auth.AuthPrincipal;
import com.jaramgle.backend.dto.UpdateProfileRequest;
import com.jaramgle.backend.dto.UserProfileDto;
import com.jaramgle.backend.dto.WithdrawRequest;
import com.jaramgle.backend.entity.User;
import com.jaramgle.backend.repository.UserRepository;
import com.jaramgle.backend.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final AccountService accountService;

    private static final String WITHDRAW_CONFIRM_TEXT = "동의합니다";

    @GetMapping("/me")
    public ResponseEntity<UserProfileDto> getMyInfo(@AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return ResponseEntity.ok(buildProfileDto(loadUser(principal.id())));
    }

    @PatchMapping("/me")
    public ResponseEntity<UserProfileDto> updateMyInfo(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = loadUser(principal.id());
        String trimmedNickname = request.nickname().trim();
        user.setName(trimmedNickname);
        userRepository.save(user);

        return ResponseEntity.ok(buildProfileDto(user));
    }

    @PostMapping("/me/withdraw")
    public ResponseEntity<Map<String, String>> withdraw(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody WithdrawRequest request
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!WITHDRAW_CONFIRM_TEXT.equals(request.confirmation())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CONFIRMATION");
        }

        accountService.withdraw(principal.id());

        ResponseCookie accessCookie = buildExpiredCookie("access_token");
        ResponseCookie refreshCookie = buildExpiredCookie("refresh_token");

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(Map.of("message", "WITHDRAWN"));
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private UserProfileDto buildProfileDto(User user) {
        return new UserProfileDto(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getProvider(),
                user.getRoleKey(),
                user.getCreatedAt()
        );
    }

    private ResponseCookie buildExpiredCookie(String name) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }
}
