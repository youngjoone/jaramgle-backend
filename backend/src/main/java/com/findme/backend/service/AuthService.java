package com.findme.backend.service;

import com.findme.backend.dto.LoginRequest;
import com.findme.backend.dto.SignupRequest;
import com.findme.backend.entity.UserEntity;
import com.findme.backend.exception.BizException;
import com.findme.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private static final Pattern PASSWORD_PATTERN = Pattern.compile("(?=.*[A-Za-z])(?=.*\\d).{8,}");

    public UserEntity signup(SignupRequest req) {
        userRepository.findByEmail(req.getEmail()).ifPresent(user -> {
            throw new BizException("EMAIL_TAKEN", "Email already taken");
        });

        if (!PASSWORD_PATTERN.matcher(req.getPassword()).matches()) {
            throw new BizException("INVALID_PASSWORD", "Password must be at least 8 characters long and contain at least one letter and one number");
        }

        UserEntity user = new UserEntity();
        user.setEmail(req.getEmail());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setNickname(req.getNickname());
        user.setEmailVerified(true);
        user.setCreatedAt(LocalDateTime.now());

        return userRepository.save(user);
    }

    public UserEntity login(LoginRequest req) {
        UserEntity user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> new BizException("INVALID_CREDENTIALS", "Invalid email or password"));

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BizException("INVALID_CREDENTIALS", "Invalid email or password");
        }
        return user;
    }
}
