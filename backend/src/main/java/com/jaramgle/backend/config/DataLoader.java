package com.jaramgle.backend.config;

import com.jaramgle.backend.entity.User;
import com.jaramgle.backend.entity.UserStatus;
import com.jaramgle.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class DataLoader implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        ensureAdminUser("jaramgle", "jaramgle123", "Admin Jaramgle");
    }

    private void ensureAdminUser(String email, String rawPassword, String name) {
        userRepository.findByEmail(email).ifPresentOrElse(user -> {
            user.setRole("ROLE_ADMIN");
            user.setStatus(UserStatus.ACTIVE);
            user.setDeleted(false);
            user.setProvider("local");
            if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
                user.setPasswordHash(passwordEncoder.encode(rawPassword));
            }
            if (user.getName() == null || user.getName().isBlank()) {
                user.setName(name);
            }
            userRepository.save(user);
            System.out.println("Admin user ensured: " + email);
        }, () -> {
            User adminUser = new User();
            adminUser.setEmail(email);
            adminUser.setPasswordHash(passwordEncoder.encode(rawPassword));
            adminUser.setName(name);
            adminUser.setProvider("local");
            adminUser.setRole("ROLE_ADMIN");
            adminUser.setStatus(UserStatus.ACTIVE);
            adminUser.setDeleted(false);
            adminUser.setCreatedAt(LocalDateTime.now());
            userRepository.save(adminUser);
            System.out.println("Admin user created: " + email);
        });
    }
}
