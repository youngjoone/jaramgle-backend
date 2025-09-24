package com.fairylearn.backend.repository;

import com.fairylearn.backend.entity.Character;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CharacterRepository extends JpaRepository<Character, Long> {
    List<Character> findByIdIn(List<Long> ids);

    List<Character> findAllByOrderByIdAsc();
}
