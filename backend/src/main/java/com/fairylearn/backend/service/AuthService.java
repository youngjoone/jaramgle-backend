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
import org.springframework.transaction.annotation.Transactional; // New import

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

    @Transactional // Ensure transactional behavior for DB operations
    public User upsertFromOAuth(String provider, String providerId, String email) {
        // Normalize email to lowercase for consistent lookup
        String normalizedEmail = email.trim().toLowerCase();

        return userRepository.findByProviderAndProviderId(provider, providerId)
                .orElseGet(() -> userRepository.findByEmail(normalizedEmail)
                        .map(existingUser -> {
                            // If user exists with email but not with provider/providerId, link them
                            if (existingUser.getProvider() == null || "local".equals(existingUser.getProvider())) {
                                existingUser.setProvider(provider);
                                existingUser.setProviderId(providerId);
                                // Update name if it's null or generic, using email as a fallback
                                if (existingUser.getName() == null || existingUser.getName().isEmpty()) {
                                    existingUser.setName(email.split("@")[0]);
                                }
                                return userRepository.saveAndFlush(existingUser); // saveAndFlush for immediate commit
                            }
                            // If email exists and is already linked to another provider, throw error
                            throw new BizException("EMAIL_ALREADY_LINKED", "Email already linked to another provider.");
                        })
                        .orElseGet(() -> {
                            // New user, create a new entry
                            User newUser = new User();
                            newUser.setEmail(normalizedEmail);
                            newUser.setProvider(provider);
                            newUser.setProviderId(providerId);
                            newUser.setName(email.split("@")[0]); // Default name from email
                            newUser.setRole("ROLE_USER"); // Default role
                            newUser.setCreatedAt(LocalDateTime.now());
                            return userRepository.saveAndFlush(newUser); // saveAndFlush for immediate commit
                        })
                );
    }
}
