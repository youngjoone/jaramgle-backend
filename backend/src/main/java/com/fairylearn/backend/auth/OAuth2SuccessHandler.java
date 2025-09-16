package com.fairylearn.backend.auth;

import com.fairylearn.backend.entity.User;
import com.fairylearn.backend.service.AuthService;
import com.fairylearn.backend.util.JwtProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final JwtProvider jwtProvider;
    private final AuthService authService;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest req, HttpServletResponse res, Authentication authentication) throws IOException {
        OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
        String registrationId = oauthToken.getAuthorizedClientRegistrationId();
        OAuth2User oauth2User = (OAuth2User) oauthToken.getPrincipal();

        // Use OAuthAttributes to handle provider differences
        OAuthAttributes attributes = OAuthAttributes.of(
                registrationId,
                oauthToken.getPrincipal().getName(), // In OIDC, this is the 'sub' claim
                oauth2User.getAttributes()
        );

        User user = authService.upsertFromOAuth(
                attributes.getProvider(),
                attributes.getProviderId(),
                attributes.getEmail()
        );

        String jwt = jwtProvider.generateToken(user);

        String redirect = frontendBaseUrl + "/auth/callback#token=" + URLEncoder.encode(jwt, StandardCharsets.UTF_8);
        res.sendRedirect(redirect);
    }
}