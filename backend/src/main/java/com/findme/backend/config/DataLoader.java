package com.findme.backend.config;

import com.findme.backend.entity.UserEntity;
import com.findme.backend.repository.UserRepository;
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
        if (userRepository.findByEmail("admin@admin.admin").isEmpty()) {
            UserEntity adminUser = new UserEntity();
            adminUser.setEmail("admin@admin.admin");
            adminUser.setPasswordHash(passwordEncoder.encode("asdf1234"));
            adminUser.setNickname("Admin");
            adminUser.setEmailVerified(true);
            adminUser.setCreatedAt(LocalDateTime.now());
            userRepository.save(adminUser);
            System.out.println("Admin user created: admin@admin.admin");
        }
    }
}
