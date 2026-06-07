package com.jaramgle.backend.repository;

import com.jaramgle.backend.entity.BusanStorySource;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BusanStorySourceRepository extends JpaRepository<BusanStorySource, Long> {

    long countByActiveTrue();

    Optional<BusanStorySource> findFirstByActiveTrueAndExternalIdOrderByQualityScoreDesc(String externalId);

    Optional<BusanStorySource> findByExternalSourceAndExternalId(String externalSource, String externalId);

    @Query("""
            SELECT s FROM BusanStorySource s
            WHERE s.active = true
              AND (COALESCE(s.thumbnailUrl, '') <> '' OR COALESCE(s.imageUrl, '') <> '')
            """)
    Page<BusanStorySource> findVisible(Pageable pageable);

    @Query("""
            SELECT s FROM BusanStorySource s
            WHERE s.active = true
              AND (COALESCE(s.thumbnailUrl, '') <> '' OR COALESCE(s.imageUrl, '') <> '')
              AND COALESCE(s.photoKeywords, '') = ''
            """)
    Page<BusanStorySource> findPhotoEnrichmentCandidates(Pageable pageable);

    @Query("""
            SELECT s FROM BusanStorySource s
            WHERE s.active = true
              AND (COALESCE(s.thumbnailUrl, '') <> '' OR COALESCE(s.imageUrl, '') <> '')
              AND LOWER(CONCAT(
                    COALESCE(s.title, ''), ' ',
                    COALESCE(s.normalizedTitle, ''), ' ',
                    COALESCE(s.district, ''), ' ',
                    COALESCE(s.subtitle, ''), ' ',
                    COALESCE(s.intro, ''), ' ',
                    COALESCE(s.feature, ''), ' ',
                    COALESCE(s.origin, ''), ' ',
                    COALESCE(s.storyContext, ''), ' ',
                    COALESCE(s.address, ''), ' ',
                    COALESCE(s.photoKeywords, '')
              )) LIKE LOWER(CONCAT('%', :keyword, '%'))
            """)
    Page<BusanStorySource> searchVisible(@Param("keyword") String keyword, Pageable pageable);
}
