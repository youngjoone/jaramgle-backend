package com.jaramgle.backend.repository;

import com.jaramgle.backend.entity.Story;
import com.jaramgle.backend.entity.StoryOrigin;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StoryRepository extends JpaRepository<Story, Long> {
    @EntityGraph(attributePaths = "characters")
    List<Story> findByUserIdAndDeletedFalseOrderByCreatedAtDesc(String userId);

    @EntityGraph(attributePaths = "characters")
    Optional<Story> findByIdAndUserIdAndDeletedFalse(Long id, String userId);

    Optional<Story> findByIdAndDeletedFalse(Long id);

    @Query("""
        SELECT s FROM Story s
        WHERE (:deleted IS NULL OR s.deleted = :deleted)
          AND (:hidden IS NULL OR s.hidden = :hidden)
          AND (:userId IS NULL OR s.userId = :userId)
          AND (:query IS NULL OR :query = '' OR LOWER(s.title) LIKE LOWER(CONCAT('%', :query, '%')))
        ORDER BY s.createdAt DESC
        """)
    Page<Story> searchForAdmin(@Param("deleted") Boolean deleted,
                               @Param("hidden") Boolean hidden,
                               @Param("userId") String userId,
                               @Param("query") String query,
                               Pageable pageable);

    @Query("""
        SELECT s FROM Story s
        WHERE s.origin = :origin
          AND s.deleted = false
          AND s.createdAt <= :createdBefore
          AND NOT EXISTS (
                SELECT 1 FROM CurriculumWeek w
                WHERE w.story = s
          )
        ORDER BY s.createdAt ASC
        """)
    List<Story> findOrphanCurriculumStoriesForCleanup(
            @Param("origin") StoryOrigin origin,
            @Param("createdBefore") LocalDateTime createdBefore,
            Pageable pageable
    );

    @Query("""
        SELECT COUNT(s) FROM Story s
        WHERE s.origin = :origin
          AND s.deleted = false
          AND s.createdAt <= :createdBefore
          AND NOT EXISTS (
                SELECT 1 FROM CurriculumWeek w
                WHERE w.story = s
          )
        """)
    long countOrphanCurriculumStories(
            @Param("origin") StoryOrigin origin,
            @Param("createdBefore") LocalDateTime createdBefore
    );
}
