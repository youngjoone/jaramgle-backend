package com.findme.backend.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.Map;

public class CustomOAuth2User implements OAuth2User {

    private OAuth2User oAuth2User;
    private Long id;
    private String email;
    private String nickname;

    public CustomOAuth2User(OAuth2User oAuth2User, Long id, String email, String nickname) {
        this.oAuth2User = oAuth2User;
        this.id = id;
        this.email = email;
        this.nickname = nickname;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return oAuth2User.getAttributes();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return oAuth2User.getAuthorities();
    }

    @Override
    public String getName() {
        return oAuth2User.getName(); // This is usually the 'sub' or 'id' from OAuth2 provider
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
}
