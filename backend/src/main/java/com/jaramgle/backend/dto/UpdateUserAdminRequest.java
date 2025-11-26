package com.jaramgle.backend.dto;

import com.jaramgle.backend.entity.UserStatus;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateUserAdminRequest {
    private UserStatus status;
    private Boolean deleted;
    private String role;
}
