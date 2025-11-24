package com.fairylearn.backend.dto;

import com.fairylearn.backend.entity.Character;
import com.fairylearn.backend.entity.CharacterModelingStatus;
import com.fairylearn.backend.entity.CharacterScope;
import com.fairylearn.backend.util.AssetUrlResolver;

public final class CharacterDtoMapper {

    private CharacterDtoMapper() {
    }

    public static CharacterDto fromEntity(Character character) {
        if (character == null) {
            return null;
        }
        String modelingStatus = character.getModelingStatus() != null
                ? character.getModelingStatus().name()
                : null;
        String scope = character.getScope() != null
                ? character.getScope().name()
                : null;
        return new CharacterDto(
                character.getId(),
                character.getSlug(),
                character.getName(),
                character.getPersona(),
                character.getCatchphrase(),
                character.getPromptKeywords(),
                AssetUrlResolver.toPublicUrl(character.getImageUrl()),
                character.getVisualDescription(),
                character.getDescriptionPrompt(),
                modelingStatus,
                scope,
                character.getArtStyle()
        );
    }
}
