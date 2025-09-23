package com.fairylearn.backend.repository;

import com.fairylearn.backend.entity.SharedStoryCommentLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SharedStoryCommentLikeRepository extends JpaRepository<SharedStoryCommentLike, Long> {

    long countByComment_Id(Long commentId);

    boolean existsByComment_IdAndUser_Id(Long commentId, Long userId);

    Optional<SharedStoryCommentLike> findByComment_IdAndUser_Id(Long commentId, Long userId);

    @Query("select cl.comment.id as commentId, count(cl.id) as likeCount from SharedStoryCommentLike cl where cl.comment.id in :commentIds group by cl.comment.id")
    List<Object[]> countByCommentIds(@Param("commentIds") List<Long> commentIds);

    @Query("select cl.comment.id from SharedStoryCommentLike cl where cl.comment.id in :commentIds and cl.user.id = :userId")
    List<Long> findLikedCommentIdsByUser(@Param("commentIds") List<Long> commentIds, @Param("userId") Long userId);
}
