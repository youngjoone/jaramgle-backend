package com.fairylearn.backend.repository;

import com.fairylearn.backend.entity.StoryPage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StoryPageRepository extends JpaRepository<StoryPage, Long> {
    List<StoryPage> findByStoryIdOrderByPageNoAsc(Long storyId);
    Optional<StoryPage> findByStoryIdAndPageNo(Long storyId, Integer pageNo);
}