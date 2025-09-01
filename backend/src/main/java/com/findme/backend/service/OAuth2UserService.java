package com.findme.backend.service;

import com.findme.backend.auth.CustomOAuth2User;
import com.findme.backend.entity.UserEntity;
import com.findme.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        String email = oAuth2User.getAttribute("email");
        String nickname = oAuth2User.getAttribute("name");
        String provider = userRequest.getClientRegistration().getRegistrationId(); // e.g., google

        Optional<UserEntity> existingUser = userRepository.findByEmail(email);
        UserEntity user;

        if (existingUser.isPresent()) {
            user = existingUser.get();
            // Update user details if necessary
            user.setNickname(nickname);
            user.setProvider(provider); // Update provider if user logs in with different provider later
        } else {
            user = new UserEntity();
            user.setEmail(email);
            user.setNickname(nickname);
            user.setProvider(provider);
            user.setCreatedAt(LocalDateTime.now());
        }
        userRepository.save(user);

        // Return OAuth2User with additional user details
        return new CustomOAuth2User(oAuth2User, user.getId(), user.getEmail(), user.getNickname());
    }
}
