package com.jaramgle.backend.repository;

import com.jaramgle.backend.entity.SharedStory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SharedStoryRepository extends JpaRepository<SharedStory, Long> {
    Optional<SharedStory> findByStoryId(Long storyId);
    Optional<SharedStory> findByStoryIdAndHiddenFalse(Long storyId);
    Optional<SharedStory> findByShareSlug(String shareSlug);
    Optional<SharedStory> findByShareSlugAndHiddenFalse(String shareSlug);
    List<SharedStory> findAllByOrderByCreatedAtDesc();
    List<SharedStory> findAllByHiddenFalseOrderByCreatedAtDesc();
    List<SharedStory> findByStoryIdIn(List<Long> storyIds);
}
