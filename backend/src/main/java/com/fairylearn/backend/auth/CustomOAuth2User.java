package com.fairylearn.backend.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.Map;
import java.util.HashMap; // Import HashMap

public class CustomOAuth2User implements OAuth2User {

    private OAuth2User oAuth2User;
    private Long id;
    private String email;
    private String nickname;
    private String provider; // Add provider field
    private Collection<? extends GrantedAuthority> authorities; // Add authorities field
    private Map<String, Object> attributes; // To store attributes if needed

    // Existing constructor for OAuth2 flow
    public CustomOAuth2User(OAuth2User oAuth2User, Long id, String email, String nickname) {
        this.oAuth2User = oAuth2User;
        this.id = id;
        this.email = email;
        this.nickname = nickname;
        this.authorities = oAuth2User.getAuthorities(); // Get authorities from OAuth2User
        this.attributes = oAuth2User.getAttributes(); // Get attributes from OAuth2User
    }

    // New constructor for JWT flow
    public CustomOAuth2User(Long id, String email, String nickname, String provider, Collection<? extends GrantedAuthority> authorities) {
        this.id = id;
        this.email = email;
        this.nickname = nickname;
        this.provider = provider;
        this.authorities = authorities;
        this.attributes = new HashMap<>(); // Initialize empty attributes for JWT flow
        this.attributes.put("id", id);
        this.attributes.put("email", email);
        this.attributes.put("nickname", nickname);
        this.attributes.put("provider", provider);
    }

    @Override
    public Map<String, Object> getAttributes() {
        return this.attributes; // Use this.attributes
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return this.authorities; // Use this.authorities
    }

    @Override
    public String getName() {
        return this.email; // Use email as name for JWT flow, or id, depending on what's unique
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getNickname() {
        return nickname;
    }

    public String getProvider() {
        return provider;
    }
}