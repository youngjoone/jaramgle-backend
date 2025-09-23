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
        user.setRole("ROLE_USER"); // Assign default role
        user.setCreatedAt(LocalDateTime.now());

        return userRepository.save(user);
    }

    public User login(LoginRequest req) {
        User user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> new BizException("INVALID_CREDENTIALS", "Invalid email or password"));

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BizException("INVALID_CREDENTIALS", "Invalid email or password");
        }

        if (user.getRoleKey() == null || user.getRoleKey().isBlank()) {
            user.setRole("ROLE_USER");
            userRepository.save(user);
        }
        return user;
    }

    @Transactional // Ensure transactional behavior for DB operations
    public User upsertFromOAuth(String provider, String providerId, String email, String nickname) {
        // Normalize email to lowercase for consistent lookup
        String normalizedEmail = email.trim().toLowerCase();
        String resolvedNickname = resolveNickname(nickname, normalizedEmail);

        return userRepository.findByProviderAndProviderId(provider, providerId)
                .map(existingUser -> updateExistingOAuthUser(existingUser, normalizedEmail, resolvedNickname))
                .orElseGet(() -> userRepository.findByEmail(normalizedEmail)
                        .map(existingUser -> linkExistingLocalUser(existingUser, provider, providerId, resolvedNickname))
                        .orElseGet(() -> createNewOAuthUser(provider, providerId, normalizedEmail, resolvedNickname))
                );
    }

    private User updateExistingOAuthUser(User user, String normalizedEmail, String resolvedNickname) {
        boolean updated = false;

        if (!normalizedEmail.equals(user.getEmail())) {
            user.setEmail(normalizedEmail);
            updated = true;
        }

        if (resolvedNickname != null && !resolvedNickname.equals(user.getName())) {
            user.setName(resolvedNickname);
            updated = true;
        }

        return updated ? userRepository.saveAndFlush(user) : user;
    }

    private User linkExistingLocalUser(User existingUser, String provider, String providerId, String resolvedNickname) {
        if (existingUser.getProvider() != null && !"local".equals(existingUser.getProvider())) {
            throw new BizException("EMAIL_ALREADY_LINKED", "Email already linked to another provider.");
        }

        existingUser.setProvider(provider);
        existingUser.setProviderId(providerId);

        if (resolvedNickname != null && !resolvedNickname.equals(existingUser.getName())) {
            existingUser.setName(resolvedNickname);
        }

        return userRepository.saveAndFlush(existingUser);
    }

    private User createNewOAuthUser(String provider, String providerId, String normalizedEmail, String resolvedNickname) {
        User newUser = new User();
        newUser.setEmail(normalizedEmail);
        newUser.setProvider(provider);
        newUser.setProviderId(providerId);
        newUser.setName(resolvedNickname);
        newUser.setRole("ROLE_USER"); // Default role
        newUser.setCreatedAt(LocalDateTime.now());
        return userRepository.saveAndFlush(newUser);
    }

    private String resolveNickname(String nickname, String email) {
        if (nickname != null && !nickname.isBlank()) {
            return nickname.trim();
        }

        int atIndex = email.indexOf('@');
        return atIndex > 0 ? email.substring(0, atIndex) : email;
    }
}
