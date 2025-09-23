package com.fairylearn.backend.auth;

import com.fairylearn.backend.entity.RefreshTokenEntity;
import com.fairylearn.backend.entity.User;
import com.fairylearn.backend.repository.RefreshTokenRepository;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final JwtProvider jwtProvider;
    private final AuthService authService;
    private final RefreshTokenRepository refreshTokenRepository; // Inject repository

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
                attributes.getEmail(),
                attributes.getName()
        );

        // Generate both access and refresh tokens
        String accessToken = jwtProvider.generateToken(user);
        String refreshToken = jwtProvider.generateRefreshToken(String.valueOf(user.getId()));
        LocalDateTime refreshTokenExpiresAt = jwtProvider.extractExpiration(refreshToken).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();

        // Save the new refresh token to the database
        RefreshTokenEntity refreshTokenEntity = new RefreshTokenEntity();
        refreshTokenEntity.setUserId(String.valueOf(user.getId()));
        refreshTokenEntity.setToken(refreshToken);
        refreshTokenEntity.setExpiresAt(refreshTokenExpiresAt);
        refreshTokenRepository.save(refreshTokenEntity);

        // Build the redirect URL with tokens as query parameters
        String redirectUrl = UriComponentsBuilder.fromUriString(frontendBaseUrl + "/auth/callback")
                .queryParam("accessToken", accessToken)
                .queryParam("refreshToken", refreshToken)
                .build().toUriString();

        res.sendRedirect(redirectUrl);
    }
}
