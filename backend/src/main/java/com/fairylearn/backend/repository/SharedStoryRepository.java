package com.fairylearn.backend.repository;

import com.fairylearn.backend.entity.SharedStory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SharedStoryRepository extends JpaRepository<SharedStory, Long> {
    Optional<SharedStory> findByStoryId(Long storyId);
    Optional<SharedStory> findByShareSlug(String shareSlug);
    List<SharedStory> findAllByOrderByCreatedAtDesc();
}
