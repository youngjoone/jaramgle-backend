package com.fairylearn.backend.repository;

import com.fairylearn.backend.entity.SharedStoryComment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SharedStoryCommentRepository extends JpaRepository<SharedStoryComment, Long> {

    @EntityGraph(attributePaths = {"user", "parent", "parent.user"})
    List<SharedStoryComment> findBySharedStory_IdOrderByCreatedAtAsc(Long sharedStoryId);

    Optional<SharedStoryComment> findByIdAndUser_Id(Long id, Long userId);

    Optional<SharedStoryComment> findByIdAndSharedStory_Id(Long id, Long sharedStoryId);

    long countBySharedStory_Id(Long sharedStoryId);

    long countBySharedStory_IdAndDeletedFalse(Long sharedStoryId);

    @Query("select c.sharedStory.id as sharedStoryId, count(c.id) as commentCount from SharedStoryComment c where c.sharedStory.id in :sharedStoryIds and c.deleted = false group by c.sharedStory.id")
    List<Object[]> countActiveCommentsBySharedStoryIds(@Param("sharedStoryIds") List<Long> sharedStoryIds);
}
