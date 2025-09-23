package com.fairylearn.backend.repository;

import com.fairylearn.backend.entity.SharedStoryLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SharedStoryLikeRepository extends JpaRepository<SharedStoryLike, Long> {

    long countBySharedStory_Id(Long sharedStoryId);

    boolean existsBySharedStory_IdAndUser_Id(Long sharedStoryId, Long userId);

    Optional<SharedStoryLike> findBySharedStory_IdAndUser_Id(Long sharedStoryId, Long userId);

    @Query("select l.sharedStory.id as sharedStoryId, count(l.id) as likeCount from SharedStoryLike l where l.sharedStory.id in :sharedStoryIds group by l.sharedStory.id")
    List<Object[]> countBySharedStoryIds(@Param("sharedStoryIds") List<Long> sharedStoryIds);
}
