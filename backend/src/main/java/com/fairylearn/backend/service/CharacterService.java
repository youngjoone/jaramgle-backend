package com.fairylearn.backend.service;

import com.fairylearn.backend.entity.Character;
import com.fairylearn.backend.entity.CharacterScope;
import com.fairylearn.backend.repository.CharacterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

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
}
