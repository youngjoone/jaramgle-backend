package com.jaramgle.backend.repository;

import com.jaramgle.backend.entity.SharedStoryBookmark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface SharedStoryBookmarkRepository extends JpaRepository<SharedStoryBookmark, Long> {
    Optional<SharedStoryBookmark> findByUserIdAndSharedStoryId(Long userId, Long sharedStoryId);

    boolean existsByUserIdAndSharedStoryId(Long userId, Long sharedStoryId);

    void deleteByUserIdAndSharedStoryId(Long userId, Long sharedStoryId);

    // For efficient checking of multiple stories
    List<SharedStoryBookmark> findByUserId(Long userId);
}
