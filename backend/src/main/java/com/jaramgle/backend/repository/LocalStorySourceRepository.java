package com.jaramgle.backend.repository;

import com.jaramgle.backend.entity.LocalStorySource;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LocalStorySourceRepository extends JpaRepository<LocalStorySource, Long> {

    long countByRegionCodeAndActiveTrue(String regionCode);

    @Query("""
            SELECT COUNT(s) FROM LocalStorySource s
            WHERE s.regionCode = :regionCode
              AND s.active = true
              AND (COALESCE(s.thumbnailUrl, '') <> '' OR COALESCE(s.imageUrl, '') <> '')
            """)
    long countVisible(@Param("regionCode") String regionCode);

    @Query("""
            SELECT COUNT(s) FROM LocalStorySource s
            WHERE s.regionCode = :regionCode
              AND s.active = true
              AND COALESCE(s.photoKeywords, '') <> ''
            """)
    long countPhotoEnriched(@Param("regionCode") String regionCode);

    @Query("SELECT MAX(s.lastSyncedAt) FROM LocalStorySource s WHERE s.regionCode = :regionCode")
    LocalDateTime findLatestSyncedAt(@Param("regionCode") String regionCode);

    Optional<LocalStorySource> findFirstByRegionCodeAndActiveTrueAndExternalIdOrderByQualityScoreDesc(String regionCode, String externalId);

    Optional<LocalStorySource> findByRegionCodeAndExternalSourceAndExternalId(String regionCode, String externalSource, String externalId);

    @Query("""
            SELECT s FROM LocalStorySource s
            WHERE s.regionCode = :regionCode
              AND s.active = true
              AND (COALESCE(s.thumbnailUrl, '') <> '' OR COALESCE(s.imageUrl, '') <> '')
            """)
    Page<LocalStorySource> findVisible(@Param("regionCode") String regionCode, Pageable pageable);

    @Query("""
            SELECT s FROM LocalStorySource s
            WHERE s.regionCode = :regionCode
              AND s.active = true
              AND (COALESCE(s.thumbnailUrl, '') <> '' OR COALESCE(s.imageUrl, '') <> '')
              AND COALESCE(s.photoKeywords, '') = ''
            """)
    Page<LocalStorySource> findPhotoEnrichmentCandidates(@Param("regionCode") String regionCode, Pageable pageable);

    @Query("""
            SELECT s FROM LocalStorySource s
            WHERE s.regionCode = :regionCode
              AND s.active = true
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
    Page<LocalStorySource> searchVisible(
            @Param("regionCode") String regionCode,
            @Param("keyword") String keyword,
            Pageable pageable
    );
}
