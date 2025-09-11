package com.fairylearn.backend.config;

import com.fairylearn.backend.entity.User;
import com.fairylearn.backend.repository.UserRepository;
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
            User adminUser = new User();
            adminUser.setEmail("admin@admin.admin");
            adminUser.setPasswordHash(passwordEncoder.encode("asdf1234"));
            adminUser.setName("Admin");
            adminUser.setProvider("local");
            adminUser.setCreatedAt(LocalDateTime.now());
            userRepository.save(adminUser);
            System.out.println("Admin user created: admin@admin.admin");
        }
    }
}
