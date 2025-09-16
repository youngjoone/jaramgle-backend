package com.fairylearn.backend.auth;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
public class CustomOidcUserService extends OidcUserService {

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest);

        // Here you can process the OidcUser attributes,
        // similar to how CustomOAuth2UserService processes OAuth2User.
        // For now, we'll just return the loaded OidcUser.
        // Later, we might want to convert it to a common UserPrincipal DTO.

        return oidcUser;
    }
}
