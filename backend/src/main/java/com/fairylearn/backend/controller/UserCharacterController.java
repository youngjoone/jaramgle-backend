package com.fairylearn.backend.controller;

import com.fairylearn.backend.auth.AuthPrincipal;
import com.fairylearn.backend.dto.CharacterDto;
import com.fairylearn.backend.dto.CharacterDtoMapper;
import com.fairylearn.backend.dto.CreateCharacterRequest;
import com.fairylearn.backend.dto.UpdateCharacterRequest;
import com.fairylearn.backend.entity.Character;
import com.fairylearn.backend.service.CharacterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/characters")
@RequiredArgsConstructor
public class UserCharacterController {

    private final CharacterService characterService;

    @GetMapping("/me")
    public List<CharacterDto> getMyCharacters(@AuthenticationPrincipal AuthPrincipal principal) {
        Long ownerId = principal.id();
        return characterService.findCustomCharacters(ownerId).stream()
                .map(CharacterDtoMapper::fromEntity)
                .collect(Collectors.toList());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CharacterDto> createCharacter(
            @RequestPart("payload") @Valid CreateCharacterRequest request,
            @RequestPart("photo") MultipartFile photo,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        Character created = characterService.createCustomCharacter(principal.id(), request, photo);
        return new ResponseEntity<>(CharacterDtoMapper.fromEntity(created), HttpStatus.CREATED);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CharacterDto updateCharacter(
            @PathVariable Long id,
            @RequestPart("payload") @Valid UpdateCharacterRequest request,
            @RequestPart(value = "photo", required = false) MultipartFile photo,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        Character updated = characterService.updateCustomCharacter(principal.id(), id, request, photo);
        return CharacterDtoMapper.fromEntity(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCharacter(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        characterService.deleteCustomCharacter(principal.id(), id);
        return ResponseEntity.noContent().build();
    }
}
