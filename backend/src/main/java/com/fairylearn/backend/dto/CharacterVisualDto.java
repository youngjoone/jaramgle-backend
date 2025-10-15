package com.fairylearn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CharacterVisualDto {
    private String name;
    private String visualDescription;
    private String imageUrl;
}