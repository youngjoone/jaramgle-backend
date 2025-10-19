package com.fairylearn.backend.repository;

import com.fairylearn.backend.entity.StorybookPage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StorybookPageRepository extends JpaRepository<StorybookPage, Long> {
    List<StorybookPage> findByStoryIdOrderByPageNumberAsc(Long storyId);

    Optional<StorybookPage> findByIdAndStoryId(Long id, Long storyId);
}
