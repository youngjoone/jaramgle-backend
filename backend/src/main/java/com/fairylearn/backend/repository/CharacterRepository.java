package com.fairylearn.backend.repository;

import com.fairylearn.backend.entity.Character;
import com.fairylearn.backend.entity.CharacterScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CharacterRepository extends JpaRepository<Character, Long> {
    List<Character> findByIdIn(List<Long> ids);

    List<Character> findAllByOrderByIdAsc();

    List<Character> findAllByScopeOrderByIdAsc(CharacterScope scope);

    Optional<Character> findBySlug(String slug);
}
