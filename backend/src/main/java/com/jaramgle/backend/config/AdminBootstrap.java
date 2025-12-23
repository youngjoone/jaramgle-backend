package com.jaramgle.backend.config;

import com.jaramgle.backend.entity.User;
import com.jaramgle.backend.entity.UserStatus;
import com.jaramgle.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap implements ApplicationRunner {

    private final UserRepository userRepository;

    @Value("${admin.bootstrap.email:}")
    private String adminEmail;

    @Override
    public void run(ApplicationArguments args) {
        if (adminEmail == null || adminEmail.isBlank()) {
            return;
        }
        userRepository.findByEmail(adminEmail.trim().toLowerCase())
                .ifPresentOrElse(user -> {
                    boolean changed = false;
                    if (!"ROLE_ADMIN".equals(user.getRole())) {
                        user.setRole("ROLE_ADMIN");
                        changed = true;
                    }
                    if (user.isDeleted()) {
                        user.setDeleted(false);
                        changed = true;
                    }
                    if (user.getStatus() != UserStatus.ACTIVE) {
                        user.setStatus(UserStatus.ACTIVE);
                        changed = true;
                    }
                    if (changed) {
                        userRepository.save(user);
                        log.info("[ADMIN_BOOTSTRAP] Elevated user {} to ROLE_ADMIN", adminEmail);
                    } else {
                        log.info("[ADMIN_BOOTSTRAP] User {} already has admin role", adminEmail);
                    }
                }, () -> log.warn("[ADMIN_BOOTSTRAP] User with email {} not found", adminEmail));
    }
}
