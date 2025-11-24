package com.fairylearn.backend.service;

import com.fairylearn.backend.dto.CharacterModelingRequestDto;
import com.fairylearn.backend.dto.CharacterModelingResponseDto;
import com.fairylearn.backend.entity.Character;
import com.fairylearn.backend.entity.CharacterModelingStatus;
import com.fairylearn.backend.exception.CharacterModelingException;
import com.fairylearn.backend.repository.CharacterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CharacterModelingService {

    private static final String MODELING_ENDPOINT = "/ai/create-character-reference-image";

    private final CharacterRepository characterRepository;
    private final WebClient webClient;

    @Async
    @Transactional
    public void requestModeling(Long characterId, String fallbackDescription, String referenceImageUri) {
        try {
            performModeling(characterId, fallbackDescription, referenceImageUri);
        } catch (CharacterModelingException ex) {
            log.error("Async character modeling failed for {}: {}", characterId, ex.getMessage(), ex);
        }
    }

    @Transactional
    public Character requestModelingSync(Long characterId, String fallbackDescription, String referenceImageUri) {
        return performModeling(characterId, fallbackDescription, referenceImageUri)
                .orElse(null);
    }

    private Optional<Character> performModeling(Long characterId, String fallbackDescription, String referenceImageUri) {
        Optional<Character> optionalCharacter = characterRepository.findById(characterId);
        if (optionalCharacter.isEmpty()) {
            log.warn("Cannot start modeling: character {} not found.", characterId);
            return Optional.empty();
        }

        Character character = optionalCharacter.get();
        if (character.getModelingStatus() == CharacterModelingStatus.IN_PROGRESS) {
            log.info("Modeling already in progress for character {} ({})", character.getSlug(), characterId);
            return Optional.of(character);
        }

        if (character.getModelingStatus() == CharacterModelingStatus.COMPLETED
                && character.getImageUrl() != null
                && !character.getImageUrl().isBlank()) {
            log.debug("Character {} ({}) already has a reference image. Skipping modeling.", character.getSlug(), characterId);
            return Optional.of(character);
        }

        character.setModelingStatus(CharacterModelingStatus.IN_PROGRESS);
        characterRepository.save(character);

        String prompt = buildPrompt(character, fallbackDescription);
        try {
            CharacterModelingRequestDto payload = new CharacterModelingRequestDto(
                    character.getName(),
                    character.getSlug(),
                    prompt,
                    referenceImageUri
            );

            CharacterModelingResponseDto response = webClient.post()
                    .uri(MODELING_ENDPOINT)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(CharacterModelingResponseDto.class)
                    .block();

            if (response == null) {
                log.error("Character modeling returned null response for {} ({}).", character.getSlug(), characterId);
                throw new CharacterModelingException("캐릭터 참조 이미지를 생성하지 못했습니다.");
            }

            String imageUrl = response.imageUrl();
            if (imageUrl == null || imageUrl.isBlank()) {
                log.error("Character modeling returned empty imageUrl for {} ({}).", character.getSlug(), characterId);
                throw new CharacterModelingException("캐릭터 참조 이미지 경로가 비어 있습니다.");
            }

            character.setImageUrl(imageUrl);
            character.setModelingStatus(CharacterModelingStatus.COMPLETED);
            if (response.metadata() != null && !response.metadata().isEmpty()) {
                log.debug("Character modeling metadata for {}: {}", character.getSlug(), response.metadata());
            }
            log.info("Character modeling completed for {} ({}).", character.getSlug(), characterId);
            return Optional.of(characterRepository.save(character));
        } catch (CharacterModelingException ex) {
            character.setModelingStatus(CharacterModelingStatus.FAILED);
            characterRepository.save(character);
            throw ex;
        } catch (Exception ex) {
            character.setModelingStatus(CharacterModelingStatus.FAILED);
            characterRepository.save(character);
            log.error("Character modeling failed for {} ({}).", character.getSlug(), characterId, ex);
            throw new CharacterModelingException("캐릭터 참조 이미지 생성 중 오류가 발생했습니다.", ex);
        }
    }

    private String buildPrompt(Character character, String fallbackDescription) {
        if (character.getDescriptionPrompt() != null && !character.getDescriptionPrompt().isBlank()) {
            return character.getDescriptionPrompt().trim();
        }
        if (fallbackDescription != null && !fallbackDescription.isBlank()) {
            return fallbackDescription.trim();
        }

        StringBuilder builder = new StringBuilder("Child-friendly illustration of ")
                .append(character.getName());

        if (character.getPersona() != null && !character.getPersona().isBlank()) {
            builder.append(" | Persona: ").append(character.getPersona());
        }

        if (character.getPromptKeywords() != null && !character.getPromptKeywords().isBlank()) {
            builder.append(" | Visual cues: ").append(character.getPromptKeywords());
        }

        if (character.getCatchphrase() != null && !character.getCatchphrase().isBlank()) {
            builder.append(" | Signature line: ").append(character.getCatchphrase());
        }
        if (character.getArtStyle() != null && !character.getArtStyle().isBlank()) {
            builder.append(" | Art style: ").append(character.getArtStyle());
        }

        builder.append(" | Friendly children's book illustration, no speech bubbles or text overlays.");
        return builder.toString();
    }
}
