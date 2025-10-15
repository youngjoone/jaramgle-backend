package com.fairylearn.backend.controller;

import com.fairylearn.backend.dto.CharacterDto;
import com.fairylearn.backend.entity.Character;
import com.fairylearn.backend.service.CharacterService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class CharacterController {

    private final CharacterService characterService;

    @GetMapping("/characters")
    public List<CharacterDto> getCharacters() {
        List<Character> characters = characterService.findAll();
        return characters.stream()
                .map(this::toDto)
                .toList();
    }

    private CharacterDto toDto(Character character) {
        return new CharacterDto(
                character.getId(),
                character.getSlug(),
                character.getName(),
                character.getPersona(),
                character.getCatchphrase(),
                character.getPromptKeywords(),
                character.getImageUrl(), // Changed from getImagePath()
                character.getVisualDescription() // Added
        );
    }
}
