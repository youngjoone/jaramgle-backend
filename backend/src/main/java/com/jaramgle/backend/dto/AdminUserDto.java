package com.jaramgle.backend.dto;

import com.jaramgle.backend.entity.User;
import com.jaramgle.backend.entity.UserStatus;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AdminUserDto {
    Long id;
    String email;
    String name;
    String provider;
    String role;
    UserStatus status;
    boolean deleted;
    LocalDateTime createdAt;

    public static AdminUserDto fromEntity(User user) {
        return AdminUserDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .provider(user.getProvider())
                .role(user.getRole())
                .status(user.getStatus())
                .deleted(user.isDeleted())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
