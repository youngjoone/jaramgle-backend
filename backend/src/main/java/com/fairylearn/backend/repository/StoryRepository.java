package com.fairylearn.backend.repository;

import com.fairylearn.backend.entity.Story;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StoryRepository extends JpaRepository<Story, Long> {
    @EntityGraph(attributePaths = "characters")
    List<Story> findByUserIdOrderByCreatedAtDesc(String userId);

    @EntityGraph(attributePaths = "characters")
    Optional<Story> findByIdAndUserId(Long id, String userId);
}
