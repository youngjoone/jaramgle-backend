package com.jaramgle.backend.dto;

import com.jaramgle.backend.entity.Character;
import com.jaramgle.backend.entity.CharacterModelingStatus;
import com.jaramgle.backend.entity.CharacterScope;
import com.jaramgle.backend.util.AssetUrlResolver;

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
