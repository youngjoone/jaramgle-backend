package com.jaramgle.backend.controller;

import com.jaramgle.backend.dto.CharacterDto;
import com.jaramgle.backend.dto.CharacterDtoMapper;
import com.jaramgle.backend.service.CharacterService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class CharacterController {

    private final CharacterService characterService;

    @GetMapping("/characters")
    public List<CharacterDto> getCharacters() {
        return characterService.findAllGlobal().stream()
                .map(CharacterDtoMapper::fromEntity)
                .toList();
    }

    @GetMapping("/characters/random")
    public List<CharacterDto> getRandomCharacters(@RequestParam(defaultValue = "1") int count) {
        if (count < 1 || count > 2) {
            throw new IllegalArgumentException("Count must be 1 or 2.");
        }
        return characterService.findRandomGlobalCharacters(count).stream()
                .map(CharacterDtoMapper::fromEntity)
                .toList();
    }
}
