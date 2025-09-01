package com.fairylearn.backend.repository;

import com.fairylearn.backend.entity.Story;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StoryRepository extends JpaRepository<Story, Long> {
    List<Story> findByUserIdOrderByCreatedAtDesc(String userId);
    Optional<Story> findByIdAndUserId(Long id, String userId);
}