package com.fairylearn.backend.service;

import com.fairylearn.backend.dto.LoginRequest;
import com.fairylearn.backend.dto.SignupRequest;
import com.fairylearn.backend.entity.User;
import com.fairylearn.backend.exception.BizException;
import com.fairylearn.backend.repository.UserRepository;
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

    public User signup(SignupRequest req) {
        userRepository.findByEmail(req.getEmail()).ifPresent(user -> {
            throw new BizException("EMAIL_TAKEN", "Email already taken");
        });

        if (!PASSWORD_PATTERN.matcher(req.getPassword()).matches()) {
            throw new BizException("INVALID_PASSWORD", "Password must be at least 8 characters long and contain at least one letter and one number");
        }

        User user = new User();
        user.setEmail(req.getEmail());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setName(req.getNickname()); // nickname -> name
        user.setProvider("local"); // Mark as local user
        user.setCreatedAt(LocalDateTime.now());

        return userRepository.save(user);
    }

    public User login(LoginRequest req) {
        User user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> new BizException("INVALID_CREDENTIALS", "Invalid email or password"));

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BizException("INVALID_CREDENTIALS", "Invalid email or password");
        }
        return user;
    }
}
