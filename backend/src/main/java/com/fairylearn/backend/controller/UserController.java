package com.fairylearn.backend.controller;

import com.fairylearn.backend.auth.AuthPrincipal;
import com.fairylearn.backend.dto.UpdateProfileRequest;
import com.fairylearn.backend.dto.UserProfileDto;
import com.fairylearn.backend.entity.User;
import com.fairylearn.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

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
                user.getRoleKey()
        );
    }
}
