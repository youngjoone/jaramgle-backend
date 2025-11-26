package com.jaramgle.backend.dto;

public record UserProfileDto(Long id, String email, String nickname, String provider, String role) {}
