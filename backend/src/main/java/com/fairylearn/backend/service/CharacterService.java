package com.fairylearn.backend.service;

import com.fairylearn.backend.dto.CreateCharacterRequest;
import com.fairylearn.backend.dto.UpdateCharacterRequest;
import com.fairylearn.backend.entity.Character;
import com.fairylearn.backend.entity.CharacterModelingStatus;
import com.fairylearn.backend.entity.CharacterScope;
import com.fairylearn.backend.entity.User;
import com.fairylearn.backend.repository.CharacterRepository;
import com.fairylearn.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CharacterService {

    private static final String CHARACTER_IMAGE_DIR =
            System.getenv().getOrDefault("CHARACTER_IMAGE_DIR", "/Users/kyj/testchardir");
    private static final String USER_PICTURE_DIR =
            System.getenv().getOrDefault("USER_PICTURE_DIR", "/Users/kyj/testpicturedir");
    private static final long MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

    private final CharacterRepository characterRepository;
    private final CharacterModelingService characterModelingService;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<Character> findAllGlobal() {
        return characterRepository.findAllByScopeOrderByIdAsc(CharacterScope.GLOBAL);
    }

    @Transactional(readOnly = true)
    public List<Character> findCustomCharacters(Long ownerId) {
        if (ownerId == null) {
            return List.of();
        }
        return characterRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    public List<Character> findByIds(List<Long> ids) {
        return characterRepository.findByIdIn(ids);
    }

    public List<Character> findRandomGlobalCharacters(int count) {
        List<Character> globalCharacters = characterRepository.findAllByScopeOrderByIdAsc(CharacterScope.GLOBAL);
        if (globalCharacters.isEmpty()) {
            return List.of();
        }
        Collections.shuffle(globalCharacters);
        return globalCharacters.stream().limit(count).collect(Collectors.toList());
    }

    @Transactional
    public Character createCustomCharacter(Long ownerId, CreateCharacterRequest request, MultipartFile photo) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        if (photo == null || photo.isEmpty()) {
            throw new IllegalArgumentException("캐릭터 이미지가 필요합니다.");
        }

        Path tempFile = storeUploadedPhoto(photo);
        String referenceImageUri = tempFile != null ? tempFile.toUri().toString() : null;

        Character character = new Character();
        character.setSlug(generateUniqueSlug(request.name(), ownerId));
        character.setName(request.name().trim());
        character.setPersona(trimToNull(request.persona()));
        character.setCatchphrase(trimToNull(request.catchphrase()));
        character.setPromptKeywords(trimToNull(request.promptKeywords()));
        character.setVisualDescription(trimToNull(request.visualDescription()));
        character.setDescriptionPrompt(trimToNull(request.descriptionPrompt()));
        character.setArtStyle(trimToNull(request.artStyle()));
        character.setScope(CharacterScope.USER);
        character.setOwner(owner);
        character.setModelingStatus(CharacterModelingStatus.PENDING);

        Character saved = characterRepository.save(character);
        try {
            characterModelingService.requestModelingSync(saved.getId(), character.getDescriptionPrompt(), referenceImageUri);
            return characterRepository.findById(saved.getId()).orElse(saved);
        } finally {
            deleteTempFile(tempFile);
        }
    }

    @Transactional
    public Character updateCustomCharacter(Long ownerId, Long characterId, UpdateCharacterRequest request, MultipartFile photo) {
        Character character = characterRepository.findByIdAndOwnerId(characterId, ownerId)
                .orElseThrow(() -> new IllegalArgumentException("캐릭터를 찾을 수 없습니다."));

        if (request.name() != null && !request.name().isBlank()) {
            character.setName(request.name().trim());
        }
        if (request.persona() != null) {
            character.setPersona(trimToNull(request.persona()));
        }
        if (request.catchphrase() != null) {
            character.setCatchphrase(trimToNull(request.catchphrase()));
        }
        if (request.promptKeywords() != null) {
            character.setPromptKeywords(trimToNull(request.promptKeywords()));
        }
        if (request.visualDescription() != null) {
            character.setVisualDescription(trimToNull(request.visualDescription()));
        }
        if (request.descriptionPrompt() != null) {
            character.setDescriptionPrompt(trimToNull(request.descriptionPrompt()));
        }
        if (request.artStyle() != null) {
            character.setArtStyle(trimToNull(request.artStyle()));
        }

        Path tempFile = storeUploadedPhoto(photo);
        String referenceImageUri = tempFile != null ? tempFile.toUri().toString() : null;

        boolean hasNewPhoto = referenceImageUri != null;
        boolean regenerate = hasNewPhoto || Boolean.TRUE.equals(request.regenerateImage());
        String previousImageUrl = character.getImageUrl();

        if (regenerate) {
            character.setModelingStatus(CharacterModelingStatus.PENDING);
        }

        Character saved = characterRepository.save(character);

        if (regenerate) {
            try {
                Character updated = characterModelingService.requestModelingSync(
                        saved.getId(),
                        saved.getDescriptionPrompt(),
                        referenceImageUri
                );
                if (updated != null) {
                    if (previousImageUrl != null && !previousImageUrl.equals(updated.getImageUrl())) {
                        deleteImageFile(previousImageUrl);
                    }
                    return updated;
                }
            } finally {
                deleteTempFile(tempFile);
            }
        } else {
            deleteTempFile(tempFile);
        }

        return saved;
    }

    @Transactional
    public void deleteCustomCharacter(Long ownerId, Long characterId) {
        Character character = characterRepository.findByIdAndOwnerId(characterId, ownerId)
                .orElseThrow(() -> new IllegalArgumentException("캐릭터를 찾을 수 없습니다."));
        String imageUrl = character.getImageUrl();
        characterRepository.delete(character);
        deleteImageFile(imageUrl);
    }

    private String generateUniqueSlug(String baseName, Long ownerId) {
        String base = slugify(baseName);
        if (base.isBlank()) {
            base = "character";
        }
        String suffix = ownerId != null ? "-" + ownerId : "";
        String candidate = base + suffix;
        int attempt = 1;
        while (characterRepository.findBySlug(candidate).isPresent()) {
            candidate = base + suffix + "-" + (++attempt);
        }
        return candidate;
    }

    private String slugify(String input) {
        if (input == null) {
            return "";
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Path storeUploadedPhoto(MultipartFile photo) {
        if (photo == null || photo.isEmpty()) {
            return null;
        }
        if (photo.getSize() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("이미지 용량은 5MB 이하만 허용됩니다.");
        }
        try {
            Files.createDirectories(Paths.get(USER_PICTURE_DIR));
            String extension = StringUtils.getFilenameExtension(photo.getOriginalFilename());
            String safeExtension = (extension == null || extension.isBlank()) ? "png" : extension.toLowerCase();
            String filename = "upload-" + UUID.randomUUID() + "." + safeExtension;
            Path target = Paths.get(USER_PICTURE_DIR, filename).toAbsolutePath().normalize();
            photo.transferTo(target.toFile());
            return target;
        } catch (IOException ex) {
            throw new IllegalStateException("이미지 업로드에 실패했습니다.", ex);
        }
    }

    private void deleteTempFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("임시 업로드 파일 삭제 실패 {}: {}", path, ex.getMessage());
        }
    }

    private void deleteImageFile(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return;
        }
        try {
            Path path;
            if (imageUrl.startsWith("file://")) {
                path = Paths.get(URI.create(imageUrl));
            } else if (imageUrl.startsWith("/characters/")) {
                String relative = imageUrl.substring("/characters/".length());
                path = Paths.get(CHARACTER_IMAGE_DIR, relative);
            } else if (!imageUrl.startsWith("http://") && !imageUrl.startsWith("https://")) {
                path = Paths.get(CHARACTER_IMAGE_DIR, imageUrl);
            } else {
                return;
            }
            Files.deleteIfExists(path);
        } catch (Exception ex) {
            log.warn("Failed to delete character image {}: {}", imageUrl, ex.getMessage());
        }
    }
}
