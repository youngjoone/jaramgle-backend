package com.jaramgle.backend.repository;

import com.jaramgle.backend.entity.CurriculumWeek;
import com.jaramgle.backend.entity.CurriculumWeekStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CurriculumWeekRepository extends JpaRepository<CurriculumWeek, Long> {

    List<CurriculumWeek> findByCurriculumIdOrderByWeekNoAsc(Long curriculumId);

    Optional<CurriculumWeek> findByCurriculumIdAndWeekNo(Long curriculumId, Integer weekNo);

    long countByCurriculumIdAndStatusIn(Long curriculumId, Collection<CurriculumWeekStatus> statuses);

    long countByCurriculumId(Long curriculumId);

    List<CurriculumWeek> findByCurriculumIdAndWeekNoGreaterThanOrderByWeekNoAsc(Long curriculumId, Integer weekNo);
}
