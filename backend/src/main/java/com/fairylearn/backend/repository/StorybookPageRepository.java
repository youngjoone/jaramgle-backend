package com.fairylearn.backend.repository;

import com.fairylearn.backend.entity.StorybookPage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StorybookPageRepository extends JpaRepository<StorybookPage, Long> {
    List<StorybookPage> findByStoryIdOrderByPageNumberAsc(Long storyId);
}