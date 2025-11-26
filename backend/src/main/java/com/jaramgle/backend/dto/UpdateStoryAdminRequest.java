package com.jaramgle.backend.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateStoryAdminRequest {
    private Boolean hidden;
    private Boolean deleted;
}
