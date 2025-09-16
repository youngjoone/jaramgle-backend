package com.fairylearn.backend.auth;

import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

public record AuthPrincipal(Long id, String email, Collection<? extends GrantedAuthority> authorities) {}
