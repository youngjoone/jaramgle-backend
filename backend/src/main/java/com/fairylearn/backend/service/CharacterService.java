package com.fairylearn.backend.service;

import com.fairylearn.backend.entity.Character;
import com.fairylearn.backend.entity.CharacterScope;
import com.fairylearn.backend.repository.CharacterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CharacterService {

    private final CharacterRepository characterRepository;

    public List<Character> findAll() {
        return characterRepository.findAllByScopeOrderByIdAsc(CharacterScope.GLOBAL);
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
}
