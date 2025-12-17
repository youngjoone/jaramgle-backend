package com.jaramgle.backend.service;

import com.jaramgle.backend.entity.Character;
import com.jaramgle.backend.entity.User;
import com.jaramgle.backend.entity.UserStatus;
import com.jaramgle.backend.repository.CharacterRepository;
import com.jaramgle.backend.repository.RefreshTokenRepository;
import com.jaramgle.backend.repository.StoryRepository;
import com.jaramgle.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {

    private static final String DELETED_EMAIL_DOMAIN = "deleted.local";
    private static final String DELETED_NAME = "탈퇴회원";

    private final UserRepository userRepository;
    private final StoryRepository storyRepository;
    private final StoryService storyService;
    private final CharacterRepository characterRepository;
    private final CharacterService characterService;
    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional
    public void withdraw(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        String userIdStr = String.valueOf(userId);

        List<Long> storyIds = storyRepository.findByUserIdAndDeletedFalseOrderByCreatedAtDesc(userIdStr)
                .stream()
                .map(story -> story.getId())
                .toList();
        if (!storyIds.isEmpty()) {
            storyService.deleteStories(storyIds, userIdStr);
        }

        List<Character> ownedCharacters = characterRepository.findAllByOwnerIdOrderByCreatedAtDesc(userId);
        for (Character character : ownedCharacters) {
            characterService.deleteCustomCharacter(userId, character.getId());
        }

        refreshTokenRepository.findAllByUserId(userIdStr)
                .forEach(token -> {
                    token.setRevokedAt(LocalDateTime.now());
                    refreshTokenRepository.save(token);
                });

        String randomSuffix = UUID.randomUUID().toString().replace("-", "");
        user.setDeleted(true);
        user.setStatus(UserStatus.SUSPENDED);
        user.setName(DELETED_NAME);
        user.setEmail("deleted_" + userId + "_" + randomSuffix + "@" + DELETED_EMAIL_DOMAIN);
        user.setProviderId("deleted_" + randomSuffix);
        user.setPasswordHash(null);
        userRepository.save(user);
    }
}
